package com.tunombre.tvbridge.mobile

import java.util.Locale

/**
 * Banderas para las selecciones: "España - Alemania" no tiene escudo en ninguna base de datos de clubes, pero un pais
 * siempre tiene bandera. Los nombres de pais salen de la propia lista de paises de Java en varios idiomas (no hay que
 * mantener una tabla), mas unos pocos alias de uso en television.
 */
object CountryFlags {
    private val LOCALES = listOf("es", "en", "fr", "de", "it", "pt", "ca", "nl").map { Locale(it) }

    // Como se nombra en television lo que no coincide con el nombre oficial.
    private val ALIASES = mapOf(
        "usa" to "us", "ee uu" to "us", "eeuu" to "us", "estados unidos de america" to "us", "united states of america" to "us",
        "inglaterra" to "gb-eng", "england" to "gb-eng", "escocia" to "gb-sct", "scotland" to "gb-sct", "gales" to "gb-wls", "wales" to "gb-wls",
        "irlanda del norte" to "gb-nir", "northern ireland" to "gb-nir", "reino unido" to "gb", "gran bretana" to "gb", "great britain" to "gb",
        "holanda" to "nl", "paises bajos" to "nl", "netherlands" to "nl", "rusia" to "ru", "chequia" to "cz", "republica checa" to "cz",
        "czechia" to "cz", "czech republic" to "cz", "turkiye" to "tr", "turquia" to "tr", "corea del sur" to "kr", "south korea" to "kr",
        "corea" to "kr", "china taipei" to "tw", "taipei chino" to "tw", "irlanda" to "ie", "eslovaquia" to "sk", "macedonia del norte" to "mk",
        "bosnia" to "ba", "bosnia y herzegovina" to "ba", "costa de marfil" to "ci", "cabo verde" to "cv", "emiratos arabes" to "ae",
        "republica dominicana" to "do", "republica democratica del congo" to "cd", "rd congo" to "cd"
    )

    private val byName: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (code in Locale.getISOCountries()) {
            for (locale in LOCALES) {
                val name = Locale("", code).getDisplayCountry(locale)
                if (name.isNotBlank()) map.putIfAbsent(SportsEvents.normalize(name).trim(), code.lowercase())
            }
        }
        map.putAll(ALIASES)
        map
    }

    // "España femenina", "Alemania Sub-21", "USA W": la misma bandera.
    private val SUFFIX = Regex("\\s+(?:femenina|femenino|women|womens|w|sub \\d{2}|u \\d{2}|u\\d{2}|masculina|masculino|men)$")

    /** El codigo de bandera (ISO en minusculas, o gb-eng...) si [name] es un pais, o null. */
    fun codeOf(name: String): String? {
        var key = SportsEvents.normalize(name).trim()
        while (true) {
            val shorter = SUFFIX.replace(key, "")
            if (shorter == key) break
            key = shorter
        }
        return byName[key]
    }

    private fun url(code: String) = "https://flagcdn.com/w160/$code.png"

    /** Las dos banderas si los dos nombres son paises (un partido entre selecciones), o null. Pedir las dos evita
     * confundir un club con el nombre de un pais ("Andorra", "Monaco"). */
    fun pair(home: String, away: String): Pair<String, String>? {
        val a = codeOf(home) ?: return null
        val b = codeOf(away) ?: return null
        return url(a) to url(b)
    }
}
