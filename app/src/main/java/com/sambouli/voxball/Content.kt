package com.sambouli.voxball

/** All the text shown in the game (French, the language of the game). */
object Content {

    const val TITLE = "LA TRAHISON DES VOIX"
    const val SUBTITLE = "PONG VOCAL — 2 JOUEURS, 1 TÉLÉPHONE"

    /** Intro hints, shown one screen at a time before the match. */
    val hints: List<Hint> = listOf(
        Hint(
            "1 / 7",
            "Posez le téléphone à plat entre vous, en mode paysage.\n\n" +
                "Joueur 1 tient le côté gauche, Joueur 2 le côté droit."
        ),
        Hint(
            "2 / 7",
            "Une seule règle de priorité : quand la balle part vers vous, " +
                "seul vous pouvez bouger votre raquette. L'autre côté est muet."
        ),
        Hint(
            "3 / 7",
            "La hauteur de votre voix fait tout :\n\n" +
                "VOIX AIGUË → la raquette monte\n" +
                "VOIX GRAVE → la raquette descend\n\n" +
                "Gardez une voix stable pour ne pas bouger."
        ),
        Hint(
            "4 / 7",
            "Votre raquette doit être PLEINE DE VOIX pour renvoyer la balle.\n\n" +
                "Silence = raquette vide, transparente : la balle la traverse " +
                "comme si elle n'existait pas."
        ),
        Hint(
            "5 / 7",
            "ROUGE : vous parlez, la raquette se remplit.\n" +
                "GRISE : vous vous taisez, elle se vide."
        ),
        Hint(
            "6 / 7",
            "Tous les 5 échanges, la balle accélère. Il n'y a pas de fin : " +
                "seul le score reste."
        ),
        Hint(
            "7 / 7",
            "Avant de jouer, chacun fredonne « mmmm » pendant deux secondes.\n\n" +
                "Le jeu apprend votre hauteur de voix : c'est votre point zéro."
        )
    )

    fun calibTitle(seat: Int) = "JOUEUR ${seat + 1} — CALIBRATION"

    const val CALIB_BODY = "Fredonnez « mmmm » en continu, à votre hauteur naturelle."

    const val CALIB_WAIT = "Le jeu écoute…"
    const val CALIB_NEED = "Il faut un son continu de la gorge, pas un souffle."

    const val TAP = "TOUCHEZ POUR CONTINUER"
    const val SKIP = "PASSER (valeur par défaut)"

    const val PAUSE = "PAUSE"
    const val RESUME = "REPRENDRE"
    const val QUIT = "QUITTER"

    const val MIC_NEEDED = "MICROPHONE NÉCESSAIRE"
    const val MIC_BODY = "Ce jeu ne se joue qu'à la voix : sans micro, les raquettes ne bougent pas."
    const val MIC_GRANT = "AUTORISER LE MICRO"

    const val SERVE = "PRÊT ?"

    fun level(n: Int) = "NIVEAU $n"

    fun yourTurn(seat: Int) = "J${seat + 1} — À TOI"
}

data class Hint(val tag: String, val body: String)
