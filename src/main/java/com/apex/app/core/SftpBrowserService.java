package com.apex.app.core;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Serwis obsługujący protokół SFTP do przeglądania i transferu plików.
 */
public class SftpBrowserService {
    private static final Logger log = LoggerFactory.getLogger(SftpBrowserService.class);

    private final ClientSession session;
    private String currentPath = ".";

    public SftpBrowserService(ClientSession session) {
        this.session = session;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public void changeDirectory(String dirName) throws IOException {
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            String targetPath = currentPath + "/" + dirName;
            currentPath = sftpClient.canonicalPath(targetPath);
            log.info("Zmieniono katalog SFTP na: {}", currentPath);
        }
    }

    public List<String> listCurrentDirectory() {
        List<String> fileNames = new ArrayList<>();
        log.info("Rozpoczęcie listowania zawartości katalogu: {}", currentPath);

        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            if (currentPath.equals(".")) {
                currentPath = sftpClient.canonicalPath(".");
            }
            
            Iterable<SftpClient.DirEntry> entries = sftpClient.readDir(currentPath);
            
            List<String> dirs = new ArrayList<>();
            List<String> files = new ArrayList<>();
            
            for (SftpClient.DirEntry entry : entries) {
                String fileName = entry.getFilename();
                if (fileName.equals(".")) continue;
                
                if (fileName.equals("..")) {
                    dirs.add("[DIR] ..");
                    continue;
                }
                
                if (entry.getAttributes().isDirectory()) {
                    dirs.add("[DIR] " + fileName);
                } else {
                    files.add("[FILE] " + fileName);
                }
            }
            
            Collections.sort(dirs);
            Collections.sort(files);
            
            fileNames.addAll(dirs);
            fileNames.addAll(files);
            
            log.info("Zakończono listowanie. Pobrano {} wpisów.", fileNames.size());

        } catch (IOException e) {
            log.error("Błąd podczas listowania katalogu SFTP: {}", currentPath, e);
            throw new RuntimeException("Nie udało się pobrać listy plików.", e);
        }

        return fileNames;
    }

    public void downloadFile(String remoteFileName, File localTarget) throws IOException {
        log.info("Pobieranie pliku: {} do {}", remoteFileName, localTarget.getAbsolutePath());
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            String remotePath = currentPath + "/" + remoteFileName;
            try (InputStream in = sftpClient.read(remotePath);
                 OutputStream out = new FileOutputStream(localTarget)) {
                in.transferTo(out);
            }
        }
        log.info("Pobieranie zakończone.");
    }

    public void uploadFile(File localFile) throws IOException {
        log.info("Wysyłanie pliku: {} do {}", localFile.getAbsolutePath(), currentPath);
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            String remotePath = currentPath + "/" + localFile.getName();
            try (InputStream in = new FileInputStream(localFile);
                 OutputStream out = sftpClient.write(remotePath)) {
                in.transferTo(out);
            }
        }
        log.info("Wysyłanie zakończone.");
    }

    /**
     * Usuwa plik lub katalog o podanej nazwie z bieżącego katalogu.
     * Dla katalogów próbuje rm -rf (rmdir obsługuje tylko puste katalogi w Apache MINA).
     *
     * @param name Nazwa elementu do usunięcia (bez ścieżki).
     * @throws IOException Gdy usunięcie się nie powiedzie (brak uprawnień, element nie istnieje).
     */
    public void deleteItem(String name) throws IOException {
        String remotePath = currentPath + "/" + name;
        log.info("Usuwanie zdalnego elementu: {}", remotePath);
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            SftpClient.Attributes attrs = sftpClient.stat(remotePath);
            if (attrs.isDirectory()) {
                sftpClient.rmdir(remotePath);
            } else {
                sftpClient.remove(remotePath);
            }
        }
        log.info("Usunięto: {}", remotePath);
    }
}
