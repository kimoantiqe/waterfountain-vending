package com.yishengkj.logging

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * LogCompressor - Compresses and decompresses log data using gzip to reduce storage
 * and network transfer costs.
 * 
 * Compression reduces log size by approximately 70-75% for typical text logs.
 */
object LogCompressor {
    
    /**
     * Compresses a string using gzip and encodes it as Base64.
     * 
     * @param data The uncompressed string data
     * @return Base64-encoded gzip-compressed data
     * @throws Exception if compression fails
     */
    fun compress(data: String): String {
        val outputStream = ByteArrayOutputStream()
        
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(data.toByteArray(Charsets.UTF_8))
        }
        
        val compressedBytes = outputStream.toByteArray()
        return Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
    }
    
    /**
     * Decompresses a Base64-encoded gzip-compressed string.
     * 
     * @param compressedData Base64-encoded gzip-compressed data
     * @return The original uncompressed string
     * @throws Exception if decompression fails
     */
    fun decompress(compressedData: String): String {
        val compressedBytes = Base64.decode(compressedData, Base64.NO_WRAP)
        
        GZIPInputStream(compressedBytes.inputStream()).use { gzip ->
            return gzip.readBytes().toString(Charsets.UTF_8)
        }
    }
    
    /**
     * Gets the size of a Base64-encoded compressed string in bytes.
     * 
     * @param compressedData Base64-encoded gzip-compressed data
     * @return Size in bytes
     */
    fun getCompressedSize(compressedData: String): Int {
        return Base64.decode(compressedData, Base64.NO_WRAP).size
    }
    
    /**
     * Gets the size of a Base64-encoded compressed string in kilobytes.
     * 
     * @param compressedData Base64-encoded gzip-compressed data
     * @return Size in KB (rounded to 2 decimal places)
     */
    fun getCompressedSizeKB(compressedData: String): Double {
        val bytes = getCompressedSize(compressedData)
        return (bytes / 1024.0 * 100).toInt() / 100.0
    }
}
