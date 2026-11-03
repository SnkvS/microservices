Feature: Manage recordings and automatically extracted song metadata through the gateway
  The sample recording has title "Test Title", artist "Test Artist", album
  "Test Album", duration "00:07" and year "2025". Its uploaded identifier must
  become the song identifier, and downloaded bytes must match the original file.

  Scenario: Upload, process, download and delete a recording
    Given an MP3 with known title, artist, album, duration and year
    When I upload the recording through the gateway
    Then matching song metadata becomes available within 60 seconds
    And downloading the resource returns the original MP3 bytes
    When I delete the resource through the gateway
    Then both resource and song metadata are unavailable
    And repeating deletion returns an empty list

  Scenario: Reject an unsupported upload content type
    When I submit a resource with content type "text/plain"
    Then the API responds with status 400 and error code "400"

  Scenario: Reject non-MP3 content claiming to be an MP3
    When I submit a resource with content type "audio/mpeg"
    Then the API responds with status 400 and error code "400"

  Scenario Outline: Validate resource and song identifiers
    When I request "<path>" through the gateway
    Then the API responds with status 400 and error code "400"
    Examples:
      | path           |
      | /resources/abc |
      | /songs/0       |

  Scenario: Reject incomplete song metadata
    When I submit song metadata without required fields
    Then the API responds with status 400 and error code "400"
