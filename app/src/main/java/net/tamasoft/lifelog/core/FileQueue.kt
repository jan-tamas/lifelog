package net.tamasoft.lifelog.core

import java.io.File

/**
 * Messages waiting for the receiver, one file each, sent oldest first and
 * deleted only after the receiver accepted them. A receiver out of reach is a
 * delay, never a loss. A message the receiver REFUSED is moved aside, not
 * retried for ever, and its count is shown on the status screen.
 */
class FileQueue(private val dir: File) {
    private val refused = File(dir, "refused")

    fun add(body: String): File = synchronized(LOCK) {
        dir.mkdirs()
        val name = "%013d-%04d.json".format(System.currentTimeMillis(), seq++ % 10000)
        val tmp = File(dir, ".$name.tmp")
        tmp.writeText(body)
        val f = File(dir, name)
        tmp.renameTo(f)
        f
    }

    fun pending(): List<File> = synchronized(LOCK) {
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") }
            ?: emptyArray()).sortedBy { it.name }
    }

    fun size(): Int = pending().size

    fun refusedCount(): Int = refused.listFiles()?.size ?: 0

    fun done(f: File) { synchronized(LOCK) { f.delete() } }

    fun refuse(f: File) = synchronized(LOCK) {
        refused.mkdirs()
        f.renameTo(File(refused, f.name))
    }

    companion object {
        private var seq = 0

        /** Every FileQueue in the process shares one lock: callers make a new one each time. */
        private val LOCK = Any()
    }
}
