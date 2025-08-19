package com.example.android_app

import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipUtil {
    fun zip(sourceDir: File, outZip: File): File {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outZip))).use { zos ->
            zipDirRecursive(sourceDir, sourceDir, zos)
        }
        return outZip
    }

    private fun zipDirRecursive(root: File, src: File, zos: ZipOutputStream) {
        if (src.isDirectory) {
            src.listFiles()?.forEach { zipDirRecursive(root, it, zos) }
        } else {
            val name = root.toURI().relativize(src.toURI()).path
            val entry = ZipEntry(name)
            zos.putNextEntry(entry)
            FileInputStream(src).use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
