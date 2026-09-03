package com.melody.local.lyrics.discovery

/** Small strict JSON reader used to keep the read-only LRCLIB client dependency-free. */
internal object SimpleJson {
    fun parse(source: String): Any? = Reader(source).parse()

    private class Reader(private val source: String) {
        private var index = 0

        fun parse(): Any? {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            require(index == source.length) { "JSON 末尾存在多余内容" }
            return value
        }

        private fun readValue(): Any? {
            skipWhitespace()
            require(index < source.length) { "JSON 意外结束" }
            return when (source[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("无效 JSON 字符，位置 $index")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            if (consume('}')) return emptyMap()
            val result = linkedMapOf<String, Any?>()
            while (true) {
                skipWhitespace()
                require(index < source.length && source[index] == '"') { "JSON 对象键必须是字符串" }
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                if (consume('}')) return result
                expect(',')
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            if (consume(']')) return emptyList()
            val result = mutableListOf<Any?>()
            while (true) {
                result += readValue()
                skipWhitespace()
                if (consume(']')) return result
                expect(',')
            }
        }

        private fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when (char) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(index < source.length) { "JSON 转义序列不完整" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(readUnicodeEscape())
                            else -> throw IllegalArgumentException("无效 JSON 转义：\\$escaped")
                        }
                    }
                    else -> {
                        require(char.code >= 0x20) { "JSON 字符串包含控制字符" }
                        result.append(char)
                    }
                }
            }
            throw IllegalArgumentException("JSON 字符串未闭合")
        }

        private fun readUnicodeEscape(): Char {
            require(index + 4 <= source.length) { "JSON Unicode 转义不完整" }
            val hex = source.substring(index, index + 4)
            index += 4
            return hex.toIntOrNull(16)?.toChar()
                ?: throw IllegalArgumentException("无效 JSON Unicode 转义")
        }

        private fun readNumber(): Number {
            val start = index
            consume('-')
            if (consume('0')) {
                // Leading zero is complete; a following digit is rejected below by number parsing.
            } else {
                require(readDigits() > 0) { "无效 JSON 数字" }
            }
            var floatingPoint = false
            if (consume('.')) {
                floatingPoint = true
                require(readDigits() > 0) { "JSON 小数缺少数字" }
            }
            if (index < source.length && source[index] in "eE") {
                floatingPoint = true
                index++
                if (index < source.length && source[index] in "+-") index++
                require(readDigits() > 0) { "JSON 指数缺少数字" }
            }
            val token = source.substring(start, index)
            return if (floatingPoint) {
                token.toDoubleOrNull()
            } else {
                token.toLongOrNull()
            } ?: throw IllegalArgumentException("无效 JSON 数字")
        }

        private fun readDigits(): Int {
            val start = index
            while (index < source.length && source[index].isDigit()) index++
            return index - start
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            require(source.regionMatches(index, literal, 0, literal.length)) { "无效 JSON 字面量" }
            index += literal.length
            return value
        }

        private fun expect(expected: Char) {
            require(consume(expected)) { "JSON 位置 $index 应为 '$expected'" }
        }

        private fun consume(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index++
            return true
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in " \t\r\n") index++
        }
    }
}
