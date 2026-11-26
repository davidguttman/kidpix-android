@file:JvmName("stamps_Alphabet")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

val KAlphabet.english: Charset by world {
    Charset(
        face = "sans-serif",
        pages = 2,
        groups = mapOf(
            "character1" to CharGroup(listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z")),
            "character2" to CharGroup(listOf("!", "?", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "-", "<", ">", "$", "%", "^", "&", "@", "*", "(", ")", "~", "|")),
        ),
    )
}

val KAlphabet.wingdings: Charset by world {
    Charset(
        face = "sans-serif",
        pages = 4,
        groups = mapOf(
            "character1" to CharGroup(listOf("✗", "◎", "✘", "❍", "✖︎", "⚬", "✕", "✧")),
            "character2" to CharGroup(listOf("❖", "◎", "◉", "⦿", "✢", "✣", "✤", "✥", "✦", "✧", "★", "☆", "✯", "✩", "✪", "✫", "✬", "✭", "✮", "✶", "✷", "✵", "✸", "✹", "✺", "❊", "✻", "✽", "✼", "❉", "✱", "✲", "✾", "❃", "❋", "✳", "✴", "❇", "❈", "※", "❅", "❆", "❄", "✿", "❀", "❁", "❂", "☙", "❧", "❦")),
            "character3" to CharGroup(listOf("♠", "♣", "♥", "♦")),
            "character4" to CharGroup(listOf("◭", "⧑", "◮", "⧒", "⧖", "◹", "⬣", "◩", "◁", "⊙", "⊖", "▩", "◵", "⬕", "◫", "⬔", "⋄", "◔", "◱", "▹", "◯", "⦶", "❏", "◷", "⬘", "⊘", "⊚", "⧨", "◀", "❐", "◰", "⬖", "⊿", "⦿", "△", "◊", "⧋", "◈", "◺", "⧊", "◿", "◶", "◸", "▪", "◎", "⬚", "⟁", "◤", "▵", "▨", "▷", "◓", "◇", "⬠", "◅", "▴", "▸", "◂", "◃", "◉", "◨", "◪", "⬙", "⬡", "◬", "⬒", "∆", "⌔", "⊝", "▣", "◣", "❍", "◒", "◥", "▰", "⊜", "◳", "▻", "⎔", "◴", "⦸", "⬢", "∇", "⬗", "▼", "▾", "◆", "⬓", "⧩", "⧫", "◧", "◕", "▧", "⊠", "❑", "⊛", "⧗", "◐", "▦", "❒", "◢", "⦾", "▿", "◑", "⟐", "▶", "▤", "▲", "◲", "⧓", "◼", "▥", "▽")),
        ),
    )
}

fun KAlphabet.nextPage() {
    KiddoPaint.Alphabet.page += 1
    if (KiddoPaint.Alphabet.page > KiddoPaint.Alphabet.english.pages) {
        KiddoPaint.Alphabet.page = 1
    }
}

/** The JS closure's `idx` (shared by every page, like upstream). */
private var KAlphabet.wingdingIdx: Int by world { 0 }

fun KAlphabet.nextWingding(page: Int): String {
    val letters = KiddoPaint.Alphabet.wingdings["character$page"]!!.letters
    if (wingdingIdx >= letters.size) {
        wingdingIdx = 0
    }
    val ret = letters[wingdingIdx]
    wingdingIdx += 1
    return ret
}
