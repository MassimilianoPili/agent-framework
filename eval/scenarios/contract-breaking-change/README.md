# Scenario: Contract Breaking Change

## Description

Tests the contract-worker's ability to detect and report breaking API changes.
A plan item modifies the OpenAPI spec in a way that introduces a breaking change
(e.g., removing a required field from a response), and the system must:
1. Detect the breaking change via oasdiff
2. Fail the quality gate for contracts
3. Block the PR from merging

## Setup

- Start with the current v1.yaml as baseline
- Task instructs the contract-worker to add a new endpoint that also
  removes an existing response property

## Expected Behavior

- Contract worker completes the spec modification
- Breaking change detection (oasdiff) reports the removed property
- Quality gate: contracts gate FAILS
- PR is not auto-merged

## Evaluation Criteria

- oasdiff correctly identifies the breaking change
- Quality gate report includes the specific breaking change details
- The system does not silently accept the breaking change
