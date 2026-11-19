Feature: Manage storage definitions through the gateway
  Scenario: Seed and manage additional storage entries
    Given both default storage types are available
    When I create an additional staging definition
    Then it appears alongside the existing staging definition
    When I delete the additional definition with a duplicate and missing ID
    Then storage deletion returns only the deleted ID and is repeatable

  Scenario: Enforce the strict storage CSV boundary
    Given both default storage types are available
    Then a 199 character storage deletion CSV succeeds and a 200 character CSV fails
