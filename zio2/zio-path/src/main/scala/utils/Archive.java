package io.github.karimagnusson.zio.path.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;


public class Archive {

     public static void tar(Path inDir, Path outFile, boolean isGzip) throws IOException {

        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile.toFile()));
             TarArchiveOutputStream tos = new TarArchiveOutputStream(
               isGzip ? new GzipCompressorOutputStream(bos) : bos)) {

            Path relDir = inDir.getParent();

            for (Path path : Files.walk(inDir).toList()) {

                File file = path.toFile();

                if (Files.isSymbolicLink(path)) {
                    continue;
                }

                TarArchiveEntry tarEntry = new TarArchiveEntry(
                  file,
                  relDir.relativize(path).toString()
                );

                tos.putArchiveEntry(tarEntry);
                if (file.isFile()) {
                    tos.write(Files.readAllBytes(path));
                }
                tos.closeArchiveEntry();
            }
            tos.finish();
        }
    }

    public static void untar(Path inFile, Path outDir, boolean isGzip) throws IOException {
        
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(inFile.toFile()));
             TarArchiveInputStream tar = new TarArchiveInputStream(
               isGzip ? new GzipCompressorInputStream(bis) : bis)) {
            
            Files.createDirectories(outDir);

            TarArchiveEntry entry;
            
            while ((entry = tar.getNextEntry()) != null) {

                Path dest = outDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(tar, dest);
                }
            }
        }
    }

    public static void zip(Path inDir, Path outFile) throws IOException {

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile.toFile()))) {

            Path parentDir = inDir.getParent();

            for (Path path : Files.walk(inDir).toList()) {

                File file = path.toFile();
                String relPath = parentDir.relativize(path).toString();

                if (Files.isSymbolicLink(path)) {
                    continue;
                }

                if (file.isFile()) {

                    zos.putNextEntry(new ZipEntry(relPath.toString()));

                    FileInputStream fis = new FileInputStream(file);

                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }

                    fis.close();

                } else {
                    
                    if (!relPath.endsWith("/")) {
                        relPath = relPath + "/";
                    }
                    
                    zos.putNextEntry(new ZipEntry(relPath.toString()));
                }

                zos.closeEntry();
            }
        }
    }

    public static void unzip(Path inFile, Path outDir) throws IOException {
        
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(inFile.toFile()))) {
            
            Files.createDirectories(outDir);

            ZipEntry entry = zis.getNextEntry();

            while ((entry = zis.getNextEntry()) != null) {

                Path dest = outDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zis, dest);
                }
            }
        }
    }
}






