package com.example.storage.service;

import com.example.storage.api.CreateStorageRequest;
import com.example.storage.model.StorageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class StorageValidationTest {
    @Test void normalizesPrefixAndKeepsRoot() {
        assertThat(StorageValidation.storage(new CreateStorageRequest(StorageType.STAGING, "valid-bucket", "/files/")).path()).isEqualTo("/files");
        assertThat(StorageValidation.storage(new CreateStorageRequest(StorageType.PERMANENT, "valid-bucket", "/")).path()).isEqualTo("/");
    }
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={"ab", "UPPERCASE", "bucket_name", "192.168.1.1", "bad..name", "-bucket", "bucket-s3alias"})
    void rejectsInvalidBuckets(String bucket) {
        assertThatIllegalArgumentException().isThrownBy(() -> StorageValidation.storage(new CreateStorageRequest(StorageType.STAGING, bucket, "/files")));
    }
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={"files", "/a//b", "/a/../b", "/a/./b", "/a\\b"})
    void rejectsInvalidPaths(String path) {
        assertThatIllegalArgumentException().isThrownBy(() -> StorageValidation.storage(new CreateStorageRequest(StorageType.STAGING, "valid-bucket", path)));
    }
    @Test void boundsUtf8PrefixLengthAndRequiresType() {
        assertThatIllegalArgumentException().isThrownBy(() -> StorageValidation.storage(new CreateStorageRequest(StorageType.STAGING, "valid-bucket", "/" + "я".repeat(450))));
        assertThatIllegalArgumentException().isThrownBy(() -> StorageValidation.storage(new CreateStorageRequest(null, "valid-bucket", "/files")));
    }
    @Test void preservesFirstOccurrenceOfPositiveIds() {
        assertThat(StorageValidation.ids("2,1,2,99")).containsExactly(2L,1L,99L);
    }
    @Test void accepts199CharactersAndRejects200() {
        assertThat(StorageValidation.ids("1,".repeat(99)+"1")).containsExactly(1L);
        assertThatIllegalArgumentException().isThrownBy(() -> StorageValidation.ids("1,".repeat(99)+"10"));
    }
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={"0","-1","1,","1,,2"," 1","1.0","9223372036854775808","a"})
    void rejectsMalformedIds(String ids) {
        assertThatIllegalArgumentException().isThrownBy(() -> StorageValidation.ids(ids));
    }
}
