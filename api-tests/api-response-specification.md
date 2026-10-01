# API Response Specification for Resource and Song Services: Expected Results for Test Validation

<!-- TOC -->
* [**Happy path**](#happy-path)
  * [**Create test song metadata (200)**](#create-test-song-metadata-200)
  * [**Upload valid MP3 resource (200)**](#upload-valid-mp3-resource-200)
  * [**Get existing resource (200)**](#get-existing-resource-200)
  * [**Get existing song metadata (200)**](#get-existing-song-metadata-200)
  * [**Delete resources with metadata (200)**](#delete-resources-with-metadata-200)
  * [**Get deleted resource (404)**](#get-deleted-resource-404)
  * [**Get deleted song metadata (404)**](#get-deleted-song-metadata-404)
* [**Error cases: Resource Service**](#error-cases-resource-service)
  * [**Upload invalid resource (400)**](#upload-invalid-resource-400)
  * [**Get non-existent resource (404)**](#get-non-existent-resource-404)
  * [**Get invalid ID - letters (400)**](#get-invalid-id---letters-400)
  * [**Get invalid ID - decimal (400)**](#get-invalid-id---decimal-400)
  * [**Get invalid ID - negative (400)**](#get-invalid-id---negative-400)
  * [**Get invalid ID - zero (400)**](#get-invalid-id---zero-400)
  * [**Delete non-existent resource (200)**](#delete-non-existent-resource-200)
  * [**Delete invalid CSV - letters (400)**](#delete-invalid-csv---letters-400)
  * [**Delete invalid CSV - length exceeded (400)**](#delete-invalid-csv---length-exceeded-400)
* [**Error cases: Song Service**](#error-cases-song-service)
  * [**Create song metadata - invalid fields - duration 02:77, year 01977 (400)**](#create-song-metadata---invalid-fields---duration-0277-year-01977-400)
  * [**Create song metadata - invalid fields - duration 0299 (400)**](#create-song-metadata---invalid-fields---duration-0299-400)
  * [**Create song metadata - invalid fields - duration 35 (400)**](#create-song-metadata---invalid-fields---duration-35-400)
  * [**Create song metadata - invalid fields - year 1 (400)**](#create-song-metadata---invalid-fields---year-1-400)
  * [**Create song metadata - invalid fields - all empty (400)**](#create-song-metadata---invalid-fields---all-empty-400)
  * [**Create song metadata - missing fields - name (400)**](#create-song-metadata---missing-fields---name-400)
  * [**Create song metadata - missing fields - all except id (400)**](#create-song-metadata---missing-fields---all-except-id-400)
  * [**Create song metadata - already exists (409)**](#create-song-metadata---already-exists-409)
  * [**Get non-existent song metadata (404)**](#get-non-existent-song-metadata-404)
  * [**Get song metadata - invalid ID - letters (400)**](#get-song-metadata---invalid-id---letters-400)
  * [**Get song metadata - invalid ID - decimal (400)**](#get-song-metadata---invalid-id---decimal-400)
  * [**Get song metadata - invalid ID - negative (400)**](#get-song-metadata---invalid-id---negative-400)
  * [**Get song metadata - invalid ID - zero (400)**](#get-song-metadata---invalid-id---zero-400)
  * [**Delete non-existent song metadata (200)**](#delete-non-existent-song-metadata-200)
  * [**Delete invalid song metadata CSV - letters (400)**](#delete-invalid-song-metadata-csv---letters-400)
  * [**Delete invalid song metadata CSV - length exceeded (400)**](#delete-invalid-song-metadata-csv---length-exceeded-400)
<!-- TOC -->

---

## **Happy path**

### **Create test song metadata (200)**
- **Method & endpoint:** `POST /songs`
- **Expected status code:** `200 OK`
- **Expected request body (with dynamic generated ID):**
  ```json
  {
    "id": {{test_id}},
    "name": "We are the champions",
    "artist": "Queen",
    "album": "News of the world",
    "duration": "02:59",
    "year": "1977"
  }
  ```
- **Expected response body:**
  ```json
  {
    "id": {{test_id}}
  }
  ```
- **Response validation:**
  - The request must include a dynamically generated `test_id` (6-digit positive integer).
  - The response must contain the same `id`.
  - The request must succeed even if no Resource with this `test_id` exists.

- **Purpose:** This request intentionally creates a standalone metadata record before any MP3 resource is uploaded. The generated `test_id` is unrelated to the Resource ID that will be created in the following steps. This setup verifies that resource metadata is identified and deleted using the Resource ID, rather than relying on database insertion order or an unrelated internal database identifier.

---

### **Upload valid MP3 resource (200)**
- **Method & endpoint:** `POST /resources`
- **Expected status code:** `200 OK`
- **Expected response body:**
  ```json
  {
    "id": 1
  }
  ```

---

### **Get existing resource (200)**
- **Method & endpoint:** `GET /resources/{id}`
- **Expected status code:** `200 OK`
- **Expected response headers:**
  - `Content-Type: audio/mpeg`
  - `Content-Length` must be present and greater than zero
- **Expected response body:**
  - Binary MP3 data

---

### **Get existing song metadata (200)**
- **Method & endpoint:** `GET /songs/{id}`
- **Expected status code:** `200 OK`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "id": 1,
    "name": "Test Title",
    "artist": "Test Artist",
    "album": "Test Album",
    "duration": "00:07",
    "year": "2025"
  }
  ```

---

### **Delete resources with metadata (200)**
- **Method & endpoint:** `DELETE /resources?id=1,101,102`
- **Expected status code:** `200 OK`
- **Expected response body:**
  ```json
  {
    "ids": [1]
  }
  ```
- **Response validation:**
  - `ids` must be an array
  - Each element in `ids` must be a number
  - `ids` array must not contain elements that don't exist

---

### **Get deleted resource (404)**
- **Method & endpoint:** `GET /resources/{id}`
- **Expected status code:** `404 Not Found`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Resource with ID=1 not found",
    "errorCode": "404"
  }
  ```

---

### **Get deleted song metadata (404)**
- **Method & endpoint:** `GET /songs/{id}`
- **Expected status code:** `404 Not Found`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Song metadata for ID=1 not found",
    "errorCode": "404"
  }
  ```

---

## **Error cases: Resource Service**

### **Upload invalid resource (400)**
- **Method & endpoint:** `POST /resources`
- **Request headers:**
  - `Content-Type: application/json`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid file format: application/json. Only MP3 files are allowed",
    "errorCode": "400"
  }
  ```

---

### **Get non-existent resource (404)**
- **Method & endpoint:** `GET /resources/99999`
- **Expected status code:** `404 Not Found`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Resource with ID=99999 not found",
    "errorCode": "404"
  }
  ```

---

### **Get invalid ID - letters (400)**
- **Method & endpoint:** `GET /resources/ABC`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid value 'ABC' for ID. Must be a positive integer",
    "errorCode": "400"
  }
  ```

---

### **Get invalid ID - decimal (400)**
- **Method & endpoint:** `GET /resources/1.1`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid value '1.1' for ID. Must be a positive integer",
    "errorCode": "400"
  }
  ```

---

### **Get invalid ID - negative (400)**
- **Method & endpoint:** `GET /resources/-1`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid value '-1' for ID. Must be a positive integer",
    "errorCode": "400"
  }
  ```

---

### **Get invalid ID - zero (400)**
- **Method & endpoint:** `GET /resources/0`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid value '0' for ID. Must be a positive integer",
    "errorCode": "400"
  }
  ```

---

### **Delete non-existent resource (200)**
- **Method & endpoint:** `DELETE /resources?id=99999`
- **Expected status code:** `200 OK`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "ids": []
  }
  ```

---

### **Delete invalid CSV - letters (400)**
- **Method & endpoint:** `DELETE /resources?id=1,2,3,4,V`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid ID format: 'V'. Only positive integers are allowed",
    "errorCode": "400"
  }
  ```

---

### **Delete invalid CSV - length exceeded (400)**
- **Method & endpoint:** `DELETE /resources?id=2147483647,2147483646,2147483645,2147483644,2147483643,2147483642,2147483641,2147483640,2147483639,2147483638,2147483637,2147483636,2147483635,2147483634,2147483633,2147483632,2147483631,2147483630,2147483629`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "CSV string is too long: received 208 characters, maximum allowed is 200",
    "errorCode": "400"
  }
  ```

---

## **Error cases: Song Service**

### **Create song metadata - invalid fields - duration 02:77, year 01977 (400)**
- **Method & endpoint:** `POST /songs`
- **Request body:**
  ```json
  {
    "id": 102,
    "name": "We are the champions",
    "artist": "Queen",
    "album": "News of the world",
    "duration": "02:77",
    "year": "01977"
  }
  ```
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Validation error",
    "details": {
      "duration": "Duration must be in mm:ss format with leading zeros",
      "year": "Year must be between 1900 and 2099"
    },
    "errorCode": "400"
  }
  ```

---

### **Create song metadata - invalid fields - duration 0299 (400)**
- **Method & endpoint:** `POST /songs`
- **Request body:**
  ```json
  {
    "id": 102,
    "name": "We are the champions",
    "artist": "Queen",
    "album": "News of the world",
    "duration": "0299",
    "year": "1977"
  }
  ```
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Validation error",
    "details": {
      "duration": "Duration must be in mm:ss format with leading zeros"
    },
    "errorCode": "400"
  }
  ```

---

### **Create song metadata - invalid fields - duration 35 (400)**
- **Method & endpoint:** `POST /songs`
- **Request body:**
  ```json
  {
    "id": 102,
    "name": "We are the champions",
    "artist": "Queen",
    "album": "News of the world",
    "duration": "35",
    "year": "1977"
  }
  ```
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Validation error",
    "details": {
      "duration": "Duration must be in mm:ss format with leading zeros"
    },
    "errorCode": "400"
  }
  ```

---

### **Create song metadata - invalid fields - year 1 (400)**
- **Method & endpoint:** `POST /songs`
- **Request body:**
  ```json
  {
    "id": 102,
    "name": "We are the champions",
    "artist": "Queen",
    "album": "News of the world",
    "duration": "02:59",
    "year": "1"
  }
  ```
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Validation error",
    "details": {
      "year": "Year must be between 1900 and 2099"
    },
    "errorCode": "400"
  }
  ```

---

### **Create song metadata - invalid fields - all empty (400)**
- **Method & endpoint:** `POST /songs`
- **Request body:**
  ```json
  {
    "id": 102,
    "name": "",
    "artist": "",
    "album": "",
    "duration": "",
    "year": ""
  }
  ```
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Validation error",
    "details": {
      "duration": "Duration must be in mm:ss format with leading zeros",
      "year": "Year must be between 1900 and 2099",
      "artist": "Artist name must be between 1 and 100 characters",
      "album": "Album name must be between 1 and 100 characters",
      "name": "Song name must be between 1 and 100 characters"
    },
    "errorCode": "400"
  }
  ```

---

### **Create song metadata - missing fields - name (400)**
- **Method & endpoint:** `POST /songs`
- **Request body:**
  ```json
  {
    "id": 103,
    "artist": "Queen",
    "album": "News of the world",
    "duration": "02:59",
    "year": "1977"
  }
  ```
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Validation error",
    "details": {
      "name": "Song name is required"
    },
    "errorCode": "400"
  }
  ```

---

### **Create song metadata - missing fields - all except id (400)**
- **Method & endpoint:** `POST /songs`
- **Request body:**
  ```json
  {
    "id": 102
  }
  ```
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Validation error",
    "details": {
      "duration": "Duration is required",
      "artist": "Artist name is required",
      "year": "Year is required",
      "album": "Album name is required",
      "name": "Song name is required"
    },
    "errorCode": "400"
  }
  ```

---

### **Create song metadata - already exists (409)**
- **Method & endpoint:** `POST /songs`
- **Request body:**
  ```json
  {
    "id": 2,
    "name": "We are the champions",
    "artist": "Queen",
    "album": "News of the world",
    "duration": "02:59",
    "year": "1977"
  }
  ```
- **Expected status code:** `409 Conflict`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Metadata for resource ID=2 already exists",
    "errorCode": "409"
  }
  ```

---

### **Get non-existent song metadata (404)**
- **Method & endpoint:** `GET /songs/99999`
- **Expected status code:** `404 Not Found`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Song metadata for ID=99999 not found",
    "errorCode": "404"
  }
  ```

---

### **Get song metadata - invalid ID - letters (400)**
- **Method & endpoint:** `GET /songs/ABC`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid value 'ABC' for ID. Must be a positive integer",
    "errorCode": "400"
  }
  ```

---

### **Get song metadata - invalid ID - decimal (400)**
- **Method & endpoint:** `GET /songs/1.1`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid value '1.1' for ID. Must be a positive integer",
    "errorCode": "400"
  }
  ```

---

### **Get song metadata - invalid ID - negative (400)**
- **Method & endpoint:** `GET /songs/-1`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid value '-1' for ID. Must be a positive integer",
    "errorCode": "400"
  }
  ```

---

### **Get song metadata - invalid ID - zero (400)**
- **Method & endpoint:** `GET /songs/0`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid value '0' for ID. Must be a positive integer",
    "errorCode": "400"
  }
  ```

---

### **Delete non-existent song metadata (200)**
- **Method & endpoint:** `DELETE /songs?id=99999`
- **Expected status code:** `200 OK`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "ids": []
  }
  ```

---

### **Delete invalid song metadata CSV - letters (400)**
- **Method & endpoint:** `DELETE /songs?id=1,2,3,4,V`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "Invalid ID format: 'V'. Only positive integers are allowed",
    "errorCode": "400"
  }
  ```

---

### **Delete invalid song metadata CSV - length exceeded (400)**
- **Method & endpoint:** `DELETE /songs?id=2147483647,2147483646,2147483645,2147483644,2147483643,2147483642,2147483641,2147483640,2147483639,2147483638,2147483637,2147483636,2147483635,2147483634,2147483633,2147483632,2147483631,2147483630,2147483629`
- **Expected status code:** `400 Bad Request`
- **Expected response headers:**
  - `Content-Type: application/json`
- **Expected response body:**
  ```json
  {
    "errorMessage": "CSV string is too long: received 208 characters, maximum allowed is 200",
    "errorCode": "400"
  }
  ```
