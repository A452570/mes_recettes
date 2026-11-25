package com.example.recettes

import com.example.recettes.data.Recipe
import java.text.Normalizer

/**
 * Met en forme un texte brut de recette (collé ou partagé) : titre, ingrédients, étapes,
 * durée et portions. Fonctionne entièrement sur le téléphone, sans connexion, par repérage
 * des rubriques ("Ingrédients", "Préparation"…) puis, à défaut, par la forme des lignes.
 * Le résultat est un brouillon que l'utilisateur relit avant de l'enregistrer.
 */
object RecipeTextParser {

    private enum class Section { NONE, INGREDIENTS, STEPS }

    private val ingredientTitles = setOf(
        "ingredients", "ingredient", "liste des ingredients", "il vous faut",
        "vous aurez besoin de", "what you need"
    )
    private val stepTitles = setOf(
        "preparation", "la preparation", "preparation de la recette", "recette", "realisation",
        "etapes", "etapes de preparation", "etapes de la recette", "instructions", "methode",
        "procedure", "deroulement", "directions", "method", "steps", "how to make it"
    )

    private val bullet = Regex("""^([•·▪◦✓☐]\s*|[-–*]\s+)""")
    private val numberedStep = Regex("""^(\d+\s*[.)]\s+|(?i:étape|etape|step)\s*\d+)""")
    private val quantityStart =
        Regex("""^(\d+([.,/]\d+)?|[½¼¾⅓⅔⅛])(\s*[-–]\s*\d+)?\s*([A-Za-zÀ-ÿŒœ]|$)""")
    private val sentenceSplit = Regex("""(?<=[.!?])\s+(?=[A-ZÀ-ÖØ-Þ])""")

    private val timeLabel = Regex(
        """^(temps total|temps de preparation|temps de cuisson|duree totale|prep time|cook time|total time|temps|duree|total|preparation|cuisson|prep|cook)\s*[:\-–]?\s*(.+)$"""
    )
    private val timeValue = Regex("""\d+\s*(h|heures?|min|mn|minutes?)""")
    private val servingsFind =
        Regex("""(\d+)\s*(personnes?|pers\b|portions?|parts?|convives?|couverts)""")
    private val servingsLabelFind =
        Regex("""(?:portions?|serves|servings|nombre de personnes)\s*:?\s*(\d+)""")
    private val servingsOnly = Regex(
        """^(pour\s+)?\d+\s*(personnes?|pers|portions?|parts?|convives?|couverts)\W*$|^(portions?|nombre de personnes|serves|servings)\s*:?\s*\d+\W*$"""
    )

    private class Meta {
        var total = ""
        var prep = ""
        var cook = ""
        var servings = ""

        fun time(): String {
            if (total.isNotBlank()) return total
            return listOfNotNull(
                prep.takeIf { it.isNotBlank() }?.let { "Préparation $it" },
                cook.takeIf { it.isNotBlank() }?.let { "Cuisson $it" }
            ).joinToString(" · ")
        }
    }

    /** Retourne null si le texte est vide. */
    fun parse(text: String): Recipe? {
        val lines = text.replace("\r", "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val meta = Meta()
        val head = mutableListOf<String>()          // lignes avant toute rubrique
        val ingredients = mutableListOf<String>()
        val steps = mutableListOf<String>()
        var section = Section.NONE
        var hasHeaders = false

        for (line in lines) {
            val header = headerOf(line)
            if (header != null) {
                hasHeaders = true
                section = header
                servingsIn(line)?.let { if (meta.servings.isBlank()) meta.servings = it }
                continue
            }
            if (consumeMeta(line, meta)) continue
            when (section) {
                Section.NONE -> head += line
                Section.INGREDIENTS -> ingredients += line
                Section.STEPS -> steps += line
            }
        }

        var title = ""
        var rest: List<String> = head
        if (head.isNotEmpty() && looksLikeTitle(head[0])) {
            title = cleanTitle(head[0])
            rest = head.drop(1)
        }

        val notes = mutableListOf<String>()
        if (!hasHeaders) {
            // Aucune rubrique : les premières lignes "courtes" sont les ingrédients, le reste la préparation.
            val firstStep = rest.indexOfFirst { !isIngredientLike(it) }
            if (firstStep == -1) {
                ingredients += rest
            } else {
                ingredients += rest.take(firstStep)
                steps += rest.drop(firstStep)
            }
        } else if (ingredients.isEmpty() && steps.isNotEmpty()) {
            val firstStep = rest.indexOfFirst { !isIngredientLike(it) }
            val cut = if (firstStep == -1) rest.size else firstStep
            ingredients += rest.take(cut)
            notes += rest.drop(cut)
        } else if (steps.isEmpty() && ingredients.isNotEmpty()) {
            // Rubrique "Ingrédients" seule : la préparation commence à la première phrase.
            val cut = ingredients.indexOfFirst { !isIngredientLike(it) }
            if (cut != -1) {
                steps += ingredients.drop(cut)
                val keep = ingredients.take(cut)
                ingredients.clear()
                ingredients += keep
            }
            notes += rest
        } else {
            notes += rest
        }

        val finalIngredients = ingredients.map(::cleanIngredient).filter { it.isNotBlank() }
        var finalSteps = (steps + notes).map(::cleanStep).filter { it.isNotBlank() }
        // Un seul gros paragraphe : on le découpe phrase par phrase.
        if (finalSteps.size == 1 && finalSteps[0].length > 150) {
            finalSteps = finalSteps[0].split(sentenceSplit).map { it.trim() }.filter { it.isNotBlank() }
        }

        if (title.isBlank() && finalIngredients.isEmpty() && finalSteps.isEmpty()) return null

        return Recipe(
            title = title,
            ingredients = finalIngredients.joinToString("\n"),
            instructions = finalSteps.joinToString("\n"),
            totalTime = meta.time(),
            servings = meta.servings
        )
    }

    // ---------------------------------------------------------------- détection

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun headerOf(line: String): Section? {
        if (line.length > 60) return null
        var core = norm(line).replace(Regex("\\(.*?\\)"), " ")
        core = core.replace(Regex("\\bpour\\s+\\d+.*$"), " ")
        core = core.trim { !it.isLetter() }.replace(Regex("\\s+"), " ")
        return when (core) {
            in ingredientTitles -> Section.INGREDIENTS
            in stepTitles -> Section.STEPS
            else -> null
        }
    }

    private fun servingsIn(line: String): String? {
        val n = norm(line)
        servingsFind.find(n)?.let { return it.value.trim() }
        servingsLabelFind.find(n)?.let { return "${it.groupValues[1]} portions" }
        return null
    }

    /** Lignes de type "Temps de cuisson : 30 min" ou "Pour 4 personnes" : on les range à part. */
    private fun consumeMeta(line: String, meta: Meta): Boolean {
        if (line.length > 60) return false
        val n = norm(line)

        if (servingsOnly.containsMatchIn(n)) {
            servingsIn(line)?.let { if (meta.servings.isBlank()) meta.servings = it }
            return true
        }

        val m = timeLabel.find(n) ?: return false
        if (!timeValue.containsMatchIn(m.groupValues[2])) return false
        val start = m.groups[2]!!.range.first.coerceAtMost(line.length)
        val value = line.substring(start).trim()
        if (value.isEmpty() || value.length > 30) return false

        val label = m.groupValues[1]
        when {
            "cuisson" in label || "cook" in label -> if (meta.cook.isBlank()) meta.cook = value
            "prepa" in label || label.startsWith("prep") -> if (meta.prep.isBlank()) meta.prep = value
            else -> if (meta.total.isBlank()) meta.total = value
        }
        return true
    }

    private fun looksLikeTitle(line: String): Boolean =
        line.length <= 100 &&
            !line.endsWith(".") &&
            !bullet.containsMatchIn(line) &&
            !quantityStart.containsMatchIn(line) &&
            !numberedStep.containsMatchIn(line)

    private fun isIngredientLike(line: String): Boolean {
        if (numberedStep.containsMatchIn(line)) return false
        if (line.length > 80) return false
        if (bullet.containsMatchIn(line)) return true
        if (quantityStart.containsMatchIn(line)) return true
        val words = line.split(Regex("\\s+")).size
        return line.length <= 45 && !line.endsWith(".") && words <= 7
    }

    // ---------------------------------------------------------------- nettoyage

    private fun cleanTitle(line: String): String =
        line.replace(Regex("^(titre|nom|recette)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .trim('#', '*', '_', ' ')
            .trim()

    private fun cleanIngredient(line: String): String = line.replace(bullet, "").trim()

    private fun cleanStep(line: String): String =
        line.replace(bullet, "")
            .replace(Regex("^(étape|etape|step)\\s*\\d+\\s*[:.)\\-–]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\d+\\s*[.)]\\s+"), "")
            .trim()
}
