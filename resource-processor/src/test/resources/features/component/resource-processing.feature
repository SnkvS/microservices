Feature: Processing resource upload notifications
  The Processor extracts metadata from uploaded recordings without running the
  Resource and Song applications. Their verified provider stubs supply HTTP responses.
  The recording has title "Test Title", artist "Test Artist", album "Test Album",
  duration "00:07" and year "2025". Processing preserves its resource identifier.

  Scenario: A recording with complete tags produces song metadata
    Given resource 42 contains an MP3 with complete tags
    When its upload notification is delivered
    Then the extracted title, artist, album, duration and year are sent to Song Service

  Scenario: An identical duplicate notification is safe
    Given resource 43 already has identical song metadata
    When its upload notification is delivered
    Then the existing metadata is checked and the notification completes successfully

  Scenario: A notification arriving after resource deletion is harmless
    Given resource 99 has already been deleted
    When its upload notification is delivered
    Then no metadata is created and the notification completes successfully
