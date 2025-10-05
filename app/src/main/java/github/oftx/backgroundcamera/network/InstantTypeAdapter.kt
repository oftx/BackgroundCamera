package github.oftx.backgroundcamera.network

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * A Gson TypeAdapter for serializing and deserializing java.time.Instant.
 * It converts an Instant to an ISO-8601 formatted string and vice versa.
 */
class InstantTypeAdapter : TypeAdapter<Instant>() {

    @Throws(IOException::class)
    override fun write(out: JsonWriter, value: Instant?) {
        if (value == null) {
            out.nullValue()
            return
        }
        // Serialize Instant to an ISO-8601 string
        out.value(DateTimeFormatter.ISO_INSTANT.format(value))
    }

    @Throws(IOException::class)
    override fun read(`in`: JsonReader): Instant? {
        if (`in`.peek() == JsonToken.NULL) {
            `in`.nextNull()
            return null
        }
        // Deserialize an ISO-8601 string to an Instant
        val dateString = `in`.nextString()
        return Instant.parse(dateString)
    }
}