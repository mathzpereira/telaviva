package app.telaviva.androidtv.network.onvif

import android.util.Xml
import java.io.StringReader
import java.net.URI
import org.xmlpull.v1.XmlPullParser

data class OnvifProfile(
    val token: String,
    val encoding: String,
    val width: Int?,
    val height: Int?,
)

enum class OnvifParseError {
    MALFORMED_XML,
    MISSING_VALUE,
    SOAP_FAULT,
}

sealed interface OnvifParseResult<out T> {
    data class Success<T>(val value: T) : OnvifParseResult<T>
    data class Error(val reason: OnvifParseError) : OnvifParseResult<Nothing>
}

object OnvifProfileParser {
    fun parseProfiles(payload: String): OnvifParseResult<List<OnvifProfile>> = parse(payload) { parser ->
        val profiles = mutableListOf<OnvifProfile>()
        var token: String? = null
        var encoding = ""
        var width: Int? = null
        var height: Int? = null
        var inVideoEncoderConfiguration = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Fault" -> throw SoapFaultSignal()
                    "Profiles" -> {
                        token = parser.getAttributeValue(null, "token")
                        encoding = ""
                        width = null
                        height = null
                    }
                    "VideoEncoderConfiguration" -> inVideoEncoderConfiguration = token != null
                    "Encoding" -> if (token != null && inVideoEncoderConfiguration) {
                        encoding = parser.nextText().trim()
                    }
                    "Width" -> if (token != null && inVideoEncoderConfiguration) {
                        width = parser.nextText().trim().toIntOrNull()
                    }
                    "Height" -> if (token != null && inVideoEncoderConfiguration) {
                        height = parser.nextText().trim().toIntOrNull()
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "VideoEncoderConfiguration" -> inVideoEncoderConfiguration = false
                    "Profiles" -> {
                        token?.takeIf(String::isNotBlank)?.let { profileToken ->
                            profiles += OnvifProfile(profileToken, encoding, width, height)
                        }
                        token = null
                    }
                }
            }
        }
        profiles
    }

    fun parseStreamUri(payload: String): OnvifParseResult<URI> {
        val parsedText = parse(payload) { parser ->
            var value: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Fault") {
                    throw SoapFaultSignal()
                }
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Uri") {
                    value = parser.nextText().trim()
                }
            }
            value
        }
        val text = when (parsedText) {
            is OnvifParseResult.Error -> return parsedText
            is OnvifParseResult.Success -> parsedText.value
        }
        if (text.isNullOrBlank()) return OnvifParseResult.Error(OnvifParseError.MISSING_VALUE)
        return try {
            OnvifParseResult.Success(URI(text))
        } catch (_: Exception) {
            OnvifParseResult.Error(OnvifParseError.MISSING_VALUE)
        }
    }

    fun parseMediaServiceUri(payload: String): OnvifParseResult<URI> {
        val parsedText = parse(payload) { parser ->
            var inMedia = false
            var value: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "Fault" -> throw SoapFaultSignal()
                        "Media" -> inMedia = true
                        "XAddr" -> if (inMedia) value = parser.nextText().trim()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "Media") inMedia = false
                }
            }
            value
        }
        val text = when (parsedText) {
            is OnvifParseResult.Error -> return parsedText
            is OnvifParseResult.Success -> parsedText.value
        }
        if (text.isNullOrBlank()) return OnvifParseResult.Error(OnvifParseError.MISSING_VALUE)
        return try {
            OnvifParseResult.Success(URI(text))
        } catch (_: Exception) {
            OnvifParseResult.Error(OnvifParseError.MISSING_VALUE)
        }
    }

    private inline fun <T> parse(payload: String, block: (XmlPullParser) -> T): OnvifParseResult<T> {
        if (payload.length > MAX_XML_CHARACTERS) {
            return OnvifParseResult.Error(OnvifParseError.MALFORMED_XML)
        }
        return try {
            OnvifParseResult.Success(block(createOnvifPullParser(payload)))
        } catch (_: SoapFaultSignal) {
            OnvifParseResult.Error(OnvifParseError.SOAP_FAULT)
        } catch (_: Exception) {
            OnvifParseResult.Error(OnvifParseError.MALFORMED_XML)
        }
    }

    private const val MAX_XML_CHARACTERS = 1_048_576
}

internal fun createOnvifPullParser(payload: String): XmlPullParser {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    parser.setInput(StringReader(payload))
    return parser
}

private class SoapFaultSignal : Exception()
