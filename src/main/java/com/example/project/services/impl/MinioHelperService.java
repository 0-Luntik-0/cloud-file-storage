package com.example.project.services.impl;

import com.example.project.dto.response.ResourceInfoResponse;
import com.example.project.exceptions.auth.AuthenticationCredentialsNotFoundException;
import com.example.project.exceptions.storage.*;
import com.example.project.utils.Resource;
import io.minio.*;
import io.minio.messages.Item;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioHelperService {

    private final MinioClient minioClient;

    public void buildZip(ZipOutputStream zipOut, InputStream fileToZip, String relativePath) {
        try {
            ZipEntry zipEntry = new ZipEntry(relativePath);
            zipOut.putNextEntry(zipEntry);

            byte[] bytes = new byte[1024];
            int length;
            while ((length = fileToZip.read(bytes)) >= 0) {
                zipOut.write(bytes, 0, length);
            }
            zipOut.closeEntry();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Произошла ошибка при сборке файлов в zip",e);
        }
    }

    public String bucketExists() {
        String bucketName = activeUserName();
        log.info("Проверяем бакет: {}", bucketName);
        try {
            if (minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucketName)
                    .build())) {
                log.debug("Бакет {} существует", bucketName);
            }
        } catch (Exception e) {
            log.error("Бакета с именем {} не существует!", bucketName);
            throw new BucketNotFoundException(String.format("Бакета '%s' не существует", bucketName));
        }

        return bucketName;
    }

    public String activeUserName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName().equals("anonymousUser")) {
            throw new AuthenticationCredentialsNotFoundException();
        }

        return authentication.getName();
    }

    public String normalizedPath(String path) {
        String normalizedPath = path.replaceAll("^/+|/+$", "").trim();
        log.info("Путь прошел нормализацию: {}  -->  {}",path,normalizedPath);

        return !normalizedPath.isEmpty() ? normalizedPath : "";
    }

    public Iterable<Result<Item>> listDirectoryContents(String bucketName, String path) {
        return minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix(path)
                .recursive(false)
                .build());
    }

    public Iterable<Result<Item>> listTopLevelOrDirectory(String bucketName, String normalizedPath) {
        Iterable<Result<Item>> results;
        if (normalizedPath.isEmpty()) {
            results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucketName)
                    .recursive(false)
                    .build());
        } else {
            results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucketName)
                    .prefix(normalizedPath + "/")
                    .recursive(false)
                    .build());
        }

        return results;
    }

    public Iterable<Result<Item>> listAllObjects(String bucketName) {
        return minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucketName)
                .recursive(true)
                .build());
    }

    public ResourceInfoResponse getResourceMetadata(String normalizedPath, String bucketName) {
        try {
            StatObjectResponse object = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(normalizedPath)
                    .build());
            String name = normalizedPath.substring(normalizedPath.lastIndexOf("/") + 1);
            String parentPath = parentPathByFullPath(normalizedPath);
            log.info("Путь {} был определён как файл", normalizedPath);
            return ResourceInfoResponse.forFile(parentPath, name, object.size());
        } catch (Exception e) {
           // затычка
        }
        if (directoryExists(bucketName,normalizedPath)) {
            String name = normalizedPath.substring(normalizedPath.lastIndexOf("/") + 1)  + "/";
            String parentPath = parentPathByFullPath(normalizedPath);
            log.info("Путь {} был определён как папка", normalizedPath);
            return ResourceInfoResponse.forDirectory(parentPath, name);
        }

        log.error("По пути {} ничего не было найдено", normalizedPath);
        throw new PathNotFoundException("Ресурс не найден");
    }

    public Resource identifyResourceType(String normalizedPath, String bucketName) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(normalizedPath)
                    .build());
            return Resource.FILE;
        } catch (Exception e) {
            // затычка
        }
        if (directoryExists(bucketName,normalizedPath)) {
            return Resource.DIRECTORY;
        }

        throw new PathNotFoundException("Папка не существует");
    }

    public String parentPathByFullPath(String normalizedPath) {
        return normalizedPath.contains("/")
                ? normalizedPath.substring(0, normalizedPath.lastIndexOf("/") + 1)
                : "";
    }

    public boolean directoryExists(String bucketName, String path) {
        Iterable<Result<Item>> results = listDirectoryContents(bucketName, path);
        for (Result<Item> r : results) {
            Item item;
            try {
                item = r.get();
            } catch (Exception e) {
                continue;
            }
            if (item.isDir()) {
                return true;
            }
        }
        return false;
    }

    public void validatePath(String path, String bucketName) {
        if (path == null || path.trim().isEmpty()) {
            log.warn("Путь: {} это бакет",bucketName);
            throw new MissingOrInvalidPathException("Невалидный или отсутствующий путь"); // 400
        }
    }

    public void deleteFile(String path, String bucketName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(path)
                    .build());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Произошла ошибка при удаление ресурса", e);
        }
    }

    public void deleteDirectory(String normalizedPath, String bucketName) {
        Iterable<Result<Item>> listObjects =  minioClient.listObjects(ListObjectsArgs.builder()
                .prefix(normalizedPath + "/")
                .bucket(bucketName)
                .recursive(true)
                .build());

        if (!listObjects.iterator().hasNext()) {
            log.error("Путь: {} ничего не содержит",normalizedPath);
            throw new PathNotFoundException("Ресурс не найден");
        }
        for (Result<Item> result : listObjects) {
            Item item;
            try {
                item = result.get();
            } catch (Exception e) {
                continue;
            }
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(item.objectName())
                        .build());
            } catch (Exception e) {
                log.error("Ресурс не найден | {}", e.getMessage());
                throw new PathNotFoundException("Ресурс не найден | " + e.getMessage());
            }
        }
    }

    public void downloadFile(String normalizedPath, String bucketName, HttpServletResponse response ) {
        try {
            String fileName = normalizedPath.substring(normalizedPath.lastIndexOf("/") + 1);

            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

            try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(normalizedPath)
                    .build());
                 OutputStream out = response.getOutputStream()) {
                inputStream.transferTo(out);
            }
            log.info("Файл по пути '{}' успешно скачен как '{}'", normalizedPath, fileName);
        }catch (Exception e) {
            log.error("Ошибка при скачивании ресурса по пути {}: {}", normalizedPath, e.getMessage());
            throw new MinioNotFoundException("Ошибка при скачивании из MinIO: " + e.getMessage());
        }
    }

    public void downloadDirectory(String normalizedPath, String bucketName,HttpServletResponse response) {
        try {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=\"archive.zip\"");

            try (ZipOutputStream zipOut = new ZipOutputStream(response.getOutputStream())) {
                Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .prefix(normalizedPath + "/")
                        .recursive(true)
                        .build());

                for (Result<Item> result : results) {
                    Item item = result.get();
                    String objectName = item.objectName();
                    if (objectName.endsWith("/")) {
                        continue;
                    }

                    try (InputStream fileToZip = minioClient.getObject(GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build())) {

                        String relativePath = objectName.substring(normalizedPath.length() + 1);
                        buildZip(zipOut, fileToZip, relativePath);
                    }
                }
                zipOut.finish();
            }
        } catch (Exception e) {
            log.error("Ошибка при скачивании ресурса по пути {}: {}", normalizedPath, e.getMessage());
            throw new MinioNotFoundException("Ошибка при скачивании из MinIO: " + e.getMessage());
        }
    }

    public void renameOrMoveFile(String normalizedNewPath, String normalizedOldPath, String bucketName) {
        try {
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(bucketName)
                    .object(normalizedNewPath)
                    .source(
                            CopySource.builder()
                                    .bucket(bucketName)
                                    .object(normalizedOldPath)
                                    .build())
                    .build());
            log.info("Файл успешно скопирован");

            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(normalizedOldPath)
                    .build());
            log.info("Старый файл успешно удален");
            log.info("Переименование файла прошло успешно");
        } catch (Exception e) {
            throw new MinioNotFoundException(String.format("Во время переименования файла произошла ошибка: %s", e.getMessage()));
        }
    }

    public void renameOrMoveDirectory(String normalizedNewPath, String normalizedOldPath, String bucketName) {
        Iterable<Result<Item>> listObjects = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix(normalizedOldPath + '/')
                .recursive(true)
                .build());

        for (Result<Item> result : listObjects) {
            Item item;
            try {
                item = result.get();
            } catch (Exception e) {
                continue;
            }
            try {
                String relativePath = item.objectName().substring(normalizedOldPath.length() + 1);
                String newObjectPath = normalizedNewPath + '/' + relativePath;

                minioClient.copyObject(CopyObjectArgs.builder()
                        .bucket(bucketName)
                        .object(newObjectPath)
                        .source(CopySource.builder()
                                .bucket(bucketName)
                                .object(item.objectName())
                                .build())
                        .build());
                log.info("Объект успешно скопирован: {}", newObjectPath);

                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(item.objectName())
                        .build());
                log.info("Старый объект успешно удален: {}", item.objectName());
            } catch (Exception e) {
                throw new MinioNotFoundException(String.format("Ошибка при обработке директории: %s", e.getMessage()));
            }
        }
    }

    public boolean doesResourceExist(String normalizedNewPath, String bucketName) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(normalizedNewPath)
                    .build());
            return true;
        }catch (Exception e) {
            log.info("Провальная проверка на файл, скорее всего это директория!");
        }
        String prefix = normalizedNewPath.endsWith("/") ? normalizedNewPath : normalizedNewPath + "/";

        Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .maxKeys(1)
                .build());

        if (results.iterator().hasNext()) {
            return true;
        }
        return false;
    }

    public Set<ResourceInfoResponse> uploadResourceToBucket(String bucketName, String normalizedPath, MultipartFile[] objects) {

        Set<ResourceInfoResponse> responses = new HashSet<>();

        for (MultipartFile file : objects) {
            String fullPath = normalizedPath + file.getOriginalFilename();
            try {
                minioClient.statObject(StatObjectArgs.builder()
                        .bucket(bucketName)
                        .object(fullPath)
                        .build());
                throw new ResourceAlreadyExistsException(fullPath);
            } catch (ResourceAlreadyExistsException e) {
                log.error("Файл '{}' уже сущестует", file.getOriginalFilename());
                throw new ResourceAlreadyExistsException(fullPath);
            } catch (Exception e) {
                // затыка
            }
            try {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(fullPath)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build());
                responses.add(ResourceInfoResponse.forFile(normalizedPath, file.getOriginalFilename(), file.getSize()));
            } catch (Exception e) {
                log.error("Ошибка при загрузке файла в MinIO: {}", e.getMessage());
            }
        }
        return  responses;
    }

    public List<ResourceInfoResponse> getFolderContent(String normalizedPath, Iterable<Result<Item>> results, Resource directoryOrFile) {
        List<ResourceInfoResponse> infoResponseList = new ArrayList<>();

        boolean found = normalizedPath.isEmpty();

        for (Result<Item> result : results) {
            try {
                Item item = result.get();
                String objectName = item.objectName();

                if (!found && !normalizedPath.isEmpty()) {
                    found = objectName.startsWith(normalizedPath + "/");
                }

                String parentPath = normalizedPath.isEmpty() ? "" : normalizedPath + "/";

                if (objectName.endsWith("/")) {
                    String name = objectName.substring(parentPath.length());
                    if (!name.isEmpty()) {
                        infoResponseList.add(ResourceInfoResponse.forDirectory(parentPath, name));
                    }
                } else {
                    String name = objectName.substring(parentPath.length());
                    infoResponseList.add(ResourceInfoResponse.forFile(parentPath, name, item.size()));
                }

            } catch (Exception e) {
                log.error("Ошибка при обработке объекта: {}", e.getMessage());
            }
        }

        if (directoryOrFile == Resource.FILE) {
            throw new MissingOrInvalidPathException("Невалидный или отсутствующий путь");
        }

        return infoResponseList;
    }

    public void parentDirectoryExists(String parentFolder, String bucketName) {
        if (!parentFolder.isEmpty()) {
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucketName)
                    .recursive(false)
                    .prefix(parentFolder)
                    .build());

            boolean parentExists = false;
            for (Result<Item> r : results) {
                Item item;
                try {
                    item = r.get();
                } catch (Exception e) {
                    continue;
                }

                if (item.objectName().equals(parentFolder)) {
                    parentExists = true;
                    break;
                }
            }

            if (!parentExists) {
                throw new PathNotFoundException("Родительская папка не существует");
            }
        }
    }

    public void createDirectory(String normalizedPath, String bucketName) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(normalizedPath + "/")
                    .stream(new ByteArrayInputStream(new byte[0]), 0, -1)
                    .build());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Ошибка при создании папки: " + e.getMessage(), e);
        }
    }
}
