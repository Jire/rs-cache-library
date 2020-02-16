package com.displee.cache

import com.displee.cache.index.Index
import com.displee.cache.index.Index.Companion.INDEX_SIZE
import com.displee.cache.index.Index255
import com.displee.cache.index.Index317
import com.displee.cache.index.ReferenceTable.Companion.FLAG_NAME
import com.displee.cache.index.ReferenceTable.Companion.FLAG_WHIRLPOOL
import com.displee.cache.index.archive.Archive
import com.displee.compress.CompressionType
import com.displee.io.Buffer
import com.displee.io.impl.OutputBuffer
import com.displee.util.Whirlpool
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigInteger
import java.util.*

open class CacheLibrary(val path: String, val clearDataAfterUpdate: Boolean = false, private val listener: ProgressListener? = null) {

    lateinit var mainFile: RandomAccessFile

    private val indices: SortedMap<Int, Index> = TreeMap<Int, Index>()
    var index255: Index255? = null

    var closed = false

    init {
        val file = File(path, "main_file_cache.dat")
        if (file.exists() && file.length() != 0L) {
            load317()
        } else {
            load()
        }
    }

    @Throws(IOException::class)
    private fun load() {
        val main = File(path, "main_file_cache.dat2")
        mainFile = if (main.exists()) {
            RandomAccessFile(main, "rw")
        } else {
            listener?.notify(-1.0, "Error, main file could not be found")
            throw FileNotFoundException("File[path=" + main.absolutePath + "] could not be found.")
        }
        val index255File = File(path, "main_file_cache.idx255")
        if (!index255File.exists()) {
            listener?.notify(-1.0, "Error, checksum file could not be found.")
            throw FileNotFoundException("File[path=" + index255File.absolutePath + "] could not be found.")
        }
        val index255 = Index255(this, RandomAccessFile(index255File, "rw"))
        this.index255 = index255
        listener?.notify(0.0, "Reading indices...")
        val indicesLength = index255.raf.length().toInt() / INDEX_SIZE
        for (i in 0 until indicesLength) {
            val file = File(path, "main_file_cache.idx$i")
            val progress = i / (indices.size - 1.0) * 100
            if (!file.exists()) {
                listener?.notify(progress, "Could not load index $i, missing idx file...")
                continue
            }
            try {
                indices[i] = Index(this, i, RandomAccessFile(file, "rw"))
                listener?.notify(progress, "Loaded index $i ...")
            } catch (e: Exception) {
                e.printStackTrace()
                listener?.notify(progress, "Failed to load index $i...")
            }
        }
    }

    @Throws(IOException::class)
    private fun load317() {
        val main = File(path, "main_file_cache.dat")
        mainFile = if (main.exists()) {
            RandomAccessFile(main, "rw")
        } else {
            listener?.notify(-1.0, "Error, main file could not be found")
            throw FileNotFoundException("File[path=" + main.absolutePath + "] could not be found.")
        }
        val indexFiles = File(path).listFiles { _: File, name: String ->
            return@listFiles name.startsWith("main_file_cache.idx")
        }
        if (indexFiles == null || indexFiles.isEmpty()) {
            throw IOException("No index files found.")
        }
        listener?.notify(0.0, "Reading indices...")
        for (i in indexFiles.indices) {
            val file = File(path, "main_file_cache.idx$i")
            val progress = i / (indices.size - 1.0) * 100
            if (!file.exists()) {
                continue
            }
            try {
                indices[i] = Index317(this, i, RandomAccessFile(file, "rw"))
                listener?.notify(progress, "Loaded index $i ...")
            } catch (e: Exception) {
                e.printStackTrace()
                listener?.notify(progress, "Failed to load index $i...")
            }
        }
    }

    @JvmOverloads
    @Throws(RuntimeException::class)
    fun createIndex(named: Boolean = false, whirlpool: Boolean = false, compressionType: CompressionType = CompressionType.GZIP): Index? {
        if (is317()) {
            throw UnsupportedOperationException("317 not supported to add new indices yet.")
        }
        val id = indices.size
        val index = Index(this, id, RandomAccessFile(File(path, "main_file_cache.idx$id"), "rw"))
        index.compressionType = compressionType
        if (named) {
            index.flagMask(FLAG_NAME)
        }
        if (whirlpool) {
            index.flagMask(FLAG_WHIRLPOOL)
        }
        index.flag()
        indices[id] = index
        check(index.update())
        return index
    }

    fun index(id: Int): Index {
        val index = indices[id]
        checkNotNull(index) { "Index $id doesn't exist. Please use the {@link exists(int) exists} function to verify whether an index exists." }
        return index
    }

    fun exists(id: Int): Boolean {
        return indices.containsKey(id)
    }

    fun put(index: Int, archive: Int, file: Int, data: ByteArray): com.displee.cache.index.archive.file.File {
        return index(index).add(archive).add(file, data)
    }

    fun put(index: Int, archive: Int, data: ByteArray): Archive {
        val currentArchive = index(index).add(archive)
        currentArchive.add(0, data)
        return currentArchive
    }

    fun put(index: Int, archive: Int, file: String, data: ByteArray): com.displee.cache.index.archive.file.File {
        return index(index).add(archive).add(file, data)
    }

    fun put(index: Int, archive: String, data: ByteArray): Archive {
        val currentArchive = index(index).add(archive)
        currentArchive.add(0, data)
        return currentArchive
    }

    fun put(index: Int, archive: String, file: String, data: ByteArray): com.displee.cache.index.archive.file.File {
        return index(index).add(archive).add(file, data)
    }

    @JvmOverloads
    fun data(index: Int, archive: Int, file: Int = 0, xtea: IntArray? = null): ByteArray? {
        return index(index).archive(archive, xtea)?.file(file)?.data
    }

    @JvmOverloads
    fun data(index: Int, archive: Int, file: String, xtea: IntArray? = null): ByteArray? {
        return index(index).archive(archive, xtea)?.file(file)?.data
    }

    @JvmOverloads
    fun data(index: Int, archive: String, file: Int, xtea: IntArray? = null): ByteArray? {
        return index(index).archive(archive, xtea)?.file(file)?.data
    }

    @JvmOverloads
    fun data(index: Int, archive: String, file: String, xtea: IntArray? = null): ByteArray? {
        return index(index).archive(archive, xtea)?.file(file)?.data
    }

    @JvmOverloads
    fun data(index: Int, archive: String, xtea: IntArray? = null): ByteArray? {
        return data(index, archive, 0, xtea)
    }

    fun remove(index: Int, archive: Int, file: Int): com.displee.cache.index.archive.file.File? {
        return index(index).archive(archive)?.remove(file)
    }

    fun remove(index: Int, archive: Int, file: String): com.displee.cache.index.archive.file.File? {
        return index(index).archive(archive)?.remove(file)
    }

    fun remove(index: Int, archive: String, file: String): com.displee.cache.index.archive.file.File? {
        return index(index).archive(archive)?.remove(file)
    }

    fun remove(index: Int, archive: Int): Archive? {
        return index(index).remove(archive)
    }

    fun remove(index: Int, archive: String): Archive? {
        return index(index).remove(archive)
    }

    @JvmOverloads
    fun update(namedXteas: Map<String, IntArray> = emptyMap()) {
        for(index in indices.values) {
            if (index.countFlaggedArchives() == 0 && !index.flagged()) {
                continue
            }
            index.update(if (index.id == 5) index.archiveNamesToIdsMap(namedXteas) else emptyMap())
        }
    }

    @Throws(RuntimeException::class)
    fun deleteLastIndex() {
        if (is317()) {
            throw UnsupportedOperationException("317 not supported to remove indices yet.")
        }
        val id = indices.size - 1
        val index = indices[id] ?: return
        index.close()
        val file = File(path, "main_file_cache.idx$id")
        if (!file.exists() || !file.delete()) {
            throw RuntimeException("Failed to remove the random access file of the argued index[id=" + id + ", file exists=" + file.exists() + "]")
        }
        index255?.raf?.setLength(id * INDEX_SIZE.toLong())
        indices.remove(id)
    }

    fun generateOldUkeys(): ByteArray {
        val buffer = OutputBuffer(indices.size * 8)
        for (index in indices()) {
            buffer.writeInt(index.crc)
            buffer.writeInt(index.revision)
        }
        return buffer.array()
    }

    fun generateNewUkeys(exponent: BigInteger, modulus: BigInteger): ByteArray {
        val buffer = OutputBuffer(indices.size * 72 + 5)
        buffer.offset = 5
        buffer.writeByte(indices.size)
        val emptyWhirlpool = ByteArray(64)
        for (index in indices()) {
            buffer.writeInt(index.crc)
            buffer.writeInt(index.revision)
            buffer.writeBytes(index.whirlpool ?: emptyWhirlpool)
        }
        val indexArray = buffer.array()
        val whirlpoolBuffer = OutputBuffer(65)
        whirlpoolBuffer.writeByte(0)//whirlpool = 64 bytes, add 1 byte because Jagex
        whirlpoolBuffer.writeBytes(Whirlpool.generate(indexArray))
        buffer.writeBytes(Buffer.cryptRSA(whirlpoolBuffer.array(), exponent, modulus))
        return buffer.array()
    }

    fun fixCrcs(update: Boolean) {
        indices.values.forEach {
            if (it.archiveIds().isEmpty()) {
                return@forEach
            }
            it.fixCRCs(update)
        }
    }

    fun close() {
        if (closed) {
            return
        }
        mainFile.close()
        index255?.close()
        indices.values.forEach { it.close() }
        closed = true
    }

    fun first(): Index? {
        if (indices.isEmpty()) {
            return null
        }
        return indices[indices.firstKey()]
    }

    fun last(): Index? {
        if (indices.isEmpty()) {
            return null
        }
        return indices[indices.lastKey()]
    }

    fun is317(): Boolean {
        return index255 == null
    }

    fun isOSRS(): Boolean {
        val index = index(2)
        return index.revision >= 300 && indices.size <= 23
    }

    fun isRS3(): Boolean {
        return indices.size > 39
    }

    fun indices(): Array<Index> {
        return indices.values.toTypedArray()
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(path: String, clearDataAfterUpdate: Boolean = false, listener: ProgressListener? = null): CacheLibrary {
            return CacheLibrary(path, clearDataAfterUpdate, listener)
        }
    }

}