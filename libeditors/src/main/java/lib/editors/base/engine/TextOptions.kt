package lib.editors.base.engine

import org.json.JSONObject
import java.nio.charset.Charset

/**
 * How a plain-text file (txt, csv) is read and saved again: its [encoding], an index in core's
 * list ([TextEncoding.ALL]), and a csv's [delimiter] ([CsvDelimiter.value]); -1 is guessed by
 * the engine when opening, and means none for txt.
 */
data class TextOptions(val encoding: Int = -1, val delimiter: Int = -1) {

    companion object {
        /** eoEngine.textOptions as JSON; null for other files. */
        fun parse(json: String): TextOptions? = runCatching {
            val options = JSONObject(json)
            TextOptions(options.getInt("encoding"), options.optInt("delimiter", -1))
        }.getOrNull()
    }
}

/** An encoding of core's (UnicodeConverter_Encodings.h): its index, Java's name and its label. */
data class TextEncoding(val index: Int, val charsetName: String, val label: String) {

    /** Java's charset, for previews; null where Android has none. */
    val charset: Charset? get() = runCatching { Charset.forName(charsetName) }.getOrNull()

    companion object {
        val ALL = listOf(
            TextEncoding(0, "ISO-8859-6", "Arabic (ISO 8859-6)"),
            TextEncoding(1, "IBM720", "Arabic (OEM 720)"),
            TextEncoding(2, "windows-1256", "Arabic (Windows)"),
            TextEncoding(3, "ISO-8859-4", "Baltic (ISO 8859-4)"),
            TextEncoding(4, "ISO-8859-13", "Baltic (ISO 8859-13)"),
            TextEncoding(5, "IBM775", "Baltic (OEM 775)"),
            TextEncoding(6, "windows-1257", "Baltic (Windows)"),
            TextEncoding(7, "ISO-8859-14", "Celtic (ISO 8859-14)"),
            TextEncoding(8, "ISO-8859-5", "Cyrillic (ISO 8859-5)"),
            TextEncoding(9, "KOI8-R", "Cyrillic (KOI8-R)"),
            TextEncoding(10, "KOI8-U", "Cyrillic (KOI8-U)"),
            TextEncoding(11, "x-MacCyrillic", "Cyrillic (Mac)"),
            TextEncoding(12, "IBM855", "Cyrillic (OEM 855)"),
            TextEncoding(13, "IBM866", "Cyrillic (OEM 866)"),
            TextEncoding(14, "windows-1251", "Cyrillic (Windows)"),
            TextEncoding(15, "IBM852", "Central European (OEM 852)"),
            TextEncoding(16, "windows-1250", "Central European (Windows)"),
            TextEncoding(17, "Big5", "Chinese (Big5 Traditional)"),
            TextEncoding(18, "GBK", "Chinese (GBK Simplified)"),
            TextEncoding(19, "ISO-8859-2", "Eastern European (ISO 8859-2)"),
            TextEncoding(20, "ISO-8859-7", "Greek (ISO 8859-7)"),
            TextEncoding(21, "x-IBM737", "Greek (OEM 737)"),
            TextEncoding(22, "IBM869", "Greek (OEM 869)"),
            TextEncoding(23, "windows-1253", "Greek (Windows)"),
            TextEncoding(24, "ISO-8859-8", "Hebrew (ISO 8859-8)"),
            TextEncoding(25, "IBM862", "Hebrew (OEM 862)"),
            TextEncoding(26, "windows-1255", "Hebrew (Windows)"),
            TextEncoding(27, "Shift_JIS", "Japanese (Shift-JIS)"),
            TextEncoding(28, "x-windows-949", "Korean (Windows)"),
            TextEncoding(29, "EUC-KR", "Korean (EUC)"),
            TextEncoding(30, "IBM861", "North European (Icelandic OEM 861)"),
            TextEncoding(31, "IBM865", "North European (Nordic OEM 865)"),
            TextEncoding(32, "x-windows-874", "Thai (TIS-620)"),
            TextEncoding(33, "ISO-8859-3", "Turkish (ISO 8859-3)"),
            TextEncoding(34, "ISO-8859-9", "Turkish (ISO 8859-9)"),
            TextEncoding(35, "IBM857", "Turkish (OEM 857)"),
            TextEncoding(36, "windows-1254", "Turkish (Windows)"),
            TextEncoding(37, "ISO-8859-1", "Western European (ISO 8859-1)"),
            TextEncoding(38, "ISO-8859-15", "Western European (ISO 8859-15)"),
            TextEncoding(39, "IBM850", "Western European (OEM 850)"),
            TextEncoding(40, "IBM00858", "Western European (OEM 858)"),
            TextEncoding(41, "IBM860", "Western European (OEM 860: Portuguese)"),
            TextEncoding(42, "IBM863", "Western European (OEM 863: French)"),
            TextEncoding(43, "IBM437", "Western European (OEM-US)"),
            TextEncoding(44, "windows-1252", "Western European (Windows)"),
            TextEncoding(45, "windows-1258", "Vietnamese (Windows)"),
            TextEncoding(46, "UTF-8", "Unicode (UTF-8)"),
            TextEncoding(47, "UTF-7", "Unicode (UTF-7)"),
            TextEncoding(48, "UTF-16LE", "Unicode (UTF-16)"),
            TextEncoding(49, "UTF-16BE", "Unicode (UTF-16 Big Endian)"),
            TextEncoding(50, "UTF-32LE", "Unicode (UTF-32)"),
            TextEncoding(51, "UTF-32BE", "Unicode (UTF-32 Big Endian)"),
            TextEncoding(52, "EUC-JP", "Japanese (EUC-JP)"),
            TextEncoding(53, "GB18030", "Chinese (GB18030)")
        )
    }
}

/** A csv's delimiter, with x2t's value (TCsvDelimiter). */
enum class CsvDelimiter(val value: Int, val char: Char) {
    COMMA(4, ','), SEMICOLON(2, ';'), TAB(1, '\t'), COLON(3, ':'), SPACE(5, ' ');

    companion object {
        fun of(value: Int): CsvDelimiter? = entries.firstOrNull { it.value == value }
    }
}
