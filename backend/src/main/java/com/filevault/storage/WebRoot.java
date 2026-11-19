package com.filevault.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class WebRoot {
    private final Resource resource;
    private final Path directory;

    public WebRoot(@Value("${filevault.web-root}") String location, ResourceLoader loader) throws IOException {
        if (location.equals("classpath:/static/")) {
            resource = new ClassPathResource("static/");
            directory = resource.exists() && resource.isFile() ? resource.getFile().toPath().toRealPath() : null;
            return;
        }
        if (!location.startsWith("file:")) {
            throw new IOException("FileVault web root must be a local file URI or classpath:/static/.");
        }
        directory = loader.getResource(location).getFile().toPath().toRealPath();
        if (!Files.isDirectory(directory)) throw new IOException("FileVault web root must be a directory.");
        resource = new FileSystemResource(directory.toString() + directory.getFileSystem().getSeparator());
    }

    public void verifyPrivate(Path storage) throws IOException {
        Path actual = resolveExistingAncestors(storage.toAbsolutePath().normalize());
        if (directory != null && (actual.startsWith(directory) || directory.startsWith(actual))) {
            throw new IOException("FileVault private storage overlaps the configured static web root.");
        }
    }

    static Path resolveExistingAncestors(Path path) throws IOException {
        if (path == null) throw new IOException("FileVault storage has no accessible root.");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return path.toRealPath();
        return resolveExistingAncestors(path.getParent()).resolve(path.getFileName());
    }

    public Resource assets() throws IOException {
        return resource.createRelative("assets/");
    }

    public Resource index() throws IOException {
        Resource index = resource.createRelative("index.html");
        return index.isReadable() && contains(index, directory) ? index : null;
    }

    public boolean containsAsset(Resource candidate) throws IOException {
        return contains(candidate, directory == null ? null : directory.resolve("assets"));
    }

    private boolean contains(Resource candidate, Path parent) throws IOException {
        if (parent == null) return !candidate.isFile();
        if (!candidate.isFile()) return false;
        Path path = candidate.getFile().toPath().toAbsolutePath().normalize();
        Path actual = path.toRealPath();
        return actual.startsWith(parent) && actual.equals(path);
    }
}
