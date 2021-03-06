package tw.mayortw.dropup.util;

/*
 * Made by dianlemel（古錐)
 * Edited by R26
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class FileUtil {

    public static void zipFiles(File zip, File srcFiles) throws IOException {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip));
        ZipFiles(out, srcFiles, new StringBuilder());
        out.close();
    }

    public static void zipFiles(OutputStream zipOut, File srcFiles) throws IOException {
        ZipOutputStream out = new ZipOutputStream(zipOut);
        ZipFiles(out, srcFiles, new StringBuilder());
        out.close();
    }

    private static void ZipFiles(ZipOutputStream out, File srcFiles, StringBuilder zipFiles)
            throws IOException {
            try {
                if (srcFiles.isDirectory()) {
                    Stream.of(srcFiles.listFiles()).forEach(files -> {
                        try {
                            if (files.isDirectory()) {
                                StringBuilder zipFiless = new StringBuilder(zipFiles);
                                zipFiless.append(files.getName()).append(File.separator);
                                out.putNextEntry(new ZipEntry(zipFiless.toString()));
                                ZipFiles(out, files, zipFiless);
                            } else {
                                ZipFiles(out, files, zipFiles);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                } else if (srcFiles.isFile()) {
                    byte[] buf = new byte[1024];
                    try (FileInputStream in = new FileInputStream(srcFiles)) {
                        out.putNextEntry(new ZipEntry(new StringBuilder(zipFiles).append(srcFiles.getName()).toString()));
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    } finally {
                        out.closeEntry();
                    }
                }
            } catch(RuntimeException e) {
                Throwable cause = e.getCause();
                if(cause instanceof IOException) {
                    throw (IOException) cause;
                } else {
                    throw e;
                }
            }
    }

    public static void unzipFiles(File file, Path dest) throws IOException {
        try(FileInputStream stream = new FileInputStream(file)) {
            unzipFiles(stream, dest);
        }
    }

    public static void unzipFiles(InputStream in, Path dest) throws IOException {

        ZipInputStream zipIn = new ZipInputStream(in);
        ZipEntry entry;

        while((entry = zipIn.getNextEntry()) != null) {

            String name = entry.getName().replaceAll("\\\\", "/"); // Some zip has backslash
            File file = dest.resolve(name).toFile();
            if(name.endsWith("/")) {
                file.mkdir();
            } else {
                try(FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buff = new byte[1024];
                    int readed; // yes I know past tense of read is read but this is better
                    while((readed = zipIn.read(buff)) != -1) {
                        out.write(buff, 0, readed);
                    }
                }
            }
        }
    }

    public static void copyDirectory(File source, File target) throws IOException {
        if (!target.exists()) {
            target.mkdir();
        }
        try {
            Stream.of(source.listFiles()).forEach(f -> {
                try {
                    if (f.isFile()) {
                        Files.copy(Paths.get(source.getAbsolutePath() + "/" + f.getName()),
                                Paths.get(target.getAbsolutePath() + "/" + f.getName()));
                    }
                    if (f.isDirectory()) {
                        File sourceDemo = new File(source.getAbsolutePath() + "/" + f.getName());
                        File destDemo = new File(target.getAbsolutePath() + "/" + f.getName());
                        copyDirectory(sourceDemo, destDemo);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch(RuntimeException e) {
            Throwable cause = e.getCause();
            if(cause instanceof IOException) {
                throw (IOException) cause;
            } else {
                throw e;
            }
        }
    }

    public static void deleteDirectory(File temp) throws IOException {
        deleteDirectory(temp.toPath());
    }

    public static void deleteDirectory(Path temp) throws IOException {
        Files.walkFileTree(temp, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult postVisitDirectory(Path file, IOException exc) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
