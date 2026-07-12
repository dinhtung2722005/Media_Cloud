package com.cloudmedia.api.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudmedia.api.entity.Folder;
import com.cloudmedia.api.repository.FolderRepository;
import com.cloudmedia.api.repository.MediaFileRepository;
@Service
public class FolderService{
    private final FolderRepository folderRepository;
    private final MediaFileRepository mediaFileRepository;
    public FolderService(FolderRepository folderRepository, MediaFileRepository mediaFileRepository) {
        this.folderRepository = folderRepository;
        this.mediaFileRepository = mediaFileRepository;
    }
    public Folder createFolder(String folderName, String parentFolderId, String userId) {
        String validParentId = (parentFolderId != null && !parentFolderId.trim().isEmpty()) ? parentFolderId : null;

        Folder folder = new Folder();
        folder.setId(UUID.randomUUID().toString());
        folder.setFolderName(folderName);
        folder.setParentFolderId(validParentId);
        folder.setUserId(userId);
        
        return folderRepository.save(folder);
    }
    public List<Folder> getFolders(String userId, String parentFolderId) {
        if (parentFolderId == null || parentFolderId.trim().isEmpty()) {
            // Lấy các thư mục nằm ở ngoài cùng (gốc - không có parent)
            return folderRepository.findByUserIdAndParentFolderIdIsNull(userId);
        }
        return folderRepository.findByUserIdAndParentFolderId(userId, parentFolderId);
    }
    public Folder getFolderById(String folderId, String userId) {
        return folderRepository.findById(folderId)
                .filter(folder -> folder.getUserId().equals(userId))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy thư mục hoặc bạn không có quyền truy cập!"));
    }
    public Folder updateFolderName(String folderId, String newFolderName, String userId) {
        Folder folder = getFolderById(folderId, userId);
        folder.setFolderName(newFolderName);
        return folderRepository.save(folder);
    }
    @Transactional
    public void deleteFolder(String folderId, String userId) {
        // 5.1. Xác thực thư mục thuộc quyền sở hữu của user
        Folder folder = getFolderById(folderId, userId);

        // 5.2. Kiểm tra xem thư mục có đang chứa thư mục con nào không
        List<Folder> subFolders = folderRepository.findByUserIdAndParentFolderId(userId, folderId);
        if (!subFolders.isEmpty()) {
            throw new RuntimeException("Không thể xóa! Thư mục này đang chứa các thư mục con. Hãy xóa hoặc di chuyển chúng trước.");
        }

        boolean hasFiles = !mediaFileRepository.findByUserIdAndFolderId(userId, folderId).isEmpty();
        if (hasFiles) {
            throw new RuntimeException("Không thể xóa! Thư mục này đang chứa các tệp tin đa phương tiện.");
        }

        folderRepository.delete(folder);
    }
}