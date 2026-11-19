package com.filevault.share;

import com.filevault.common.ApiException;
import com.filevault.file.FileService;
import com.filevault.user.UserMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class ShareService {
    private final UserMapper users;
    private final ShareMapper shares;
    private final FileService files;

    ShareService(UserMapper users, ShareMapper shares, FileService files) {
        this.users = users;
        this.shares = shares;
        this.files = files;
    }

    record Created(String id, LocalDateTime expiresAt, String url) { }

    Created create(String username, UUID fileId, int minutes) {
        files.download(username, fileId);
        long owner = owner(username);
        String id = UUID.randomUUID().toString();
        String token = ShareToken.newToken();
        var expiry = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(minutes);
        shares.insert(id, owner, fileId.toString(), ShareToken.digest(token), expiry);
        return new Created(id, expiry, "/api/v1/public/shares/" + token);
    }

    void revoke(String username, UUID id) {
        long owner = owner(username);
        if (shares.revoke(owner, id.toString()) == 0 && shares.owns(owner, id.toString()) == 0) {
            throw missing();
        }
    }

    ShareMapper.SharedFile resolve(String token) {
        if (!ShareToken.validFormat(token)) {
            throw missing();
        }
        var file = shares.findActive(ShareToken.digest(token));
        if (file == null) {
            throw missing();
        }
        return file;
    }

    private long owner(String username) {
        var account = users.find(username);
        if (account == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in first.");
        }
        return account.id();
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "SHARE_NOT_FOUND", "The share is unavailable.");
    }
}
