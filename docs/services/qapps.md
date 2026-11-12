# Amazon Q Apps

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/apps.create` and the rest, signed for `qapps`, with the `instance-id` header

Q Apps runs on a [Q Business](qbusiness.md) application with `qAppsConfiguration.qAppsControlMode` set to `ENABLED`. That
application is the instance. Instances that don't exist are `ResourceNotFoundException`; ones with Q Apps disabled are
`AccessDeniedException`.

## Apps

An app is a list of cards: text inputs, Q queries, Q plugins, file uploads and forms, with unique ids and titles. A
card's prompt references other cards by `@Title`; those are its dependencies, and they can't form a cycle.
`requiredCapabilities` follows the cards. Every `UpdateQApp` makes a new version, and older versions stay readable and
runnable.

## Sessions

`StartQAppSession` fills the input cards from `initialValues` and the cards' defaults. Then it computes every query card
whose dependencies are complete, with the references replaced by their values. An `approved-sources` card answers from
the instance's index and the session's files, citing nothing it can't find; an `llm` card answers from Floci's Bedrock
runtime. `UpdateQAppSession` sets values and adds, edits or deletes form submissions, then recomputes. A session is
`WAITING` until every card is complete. Its submissions list with `ListQAppSessionData` and export as CSV.

Files come from `ImportDocument` or a presigned upload (`CreatePresignedUrl`, checked against `fileContentsSha256`).
A session-scoped file fills its card; an application-scoped one becomes the card's default.

## Library

Categories (up to 10 per instance) group library items. Publishing an app version makes the app `PUBLISHED`.
Reviews count ratings once per user, and items can be verified. `AssociateQAppWithUser` adds a library app to the
user's list. `PredictQApp` drafts an app from a problem statement or a conversation. Permissions grant other users read
or write.

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateQApp` | Create an app |
| `GetQApp` | Read an app version |
| `UpdateQApp` | Make a new app version |
| `DeleteQApp` | Delete an app |
| `ListQApps` | List your apps |
| `DescribeQAppPermissions` | Read an app's permissions |
| `UpdateQAppPermissions` | Grant or revoke app permissions |
| `AssociateQAppWithUser` | Add a library app to your list |
| `DisassociateQAppFromUser` | Remove an app from your list |
| `StartQAppSession` | Start a session and compute its cards |
| `GetQAppSession` | Read a session's cards |
| `UpdateQAppSession` | Set card values and recompute |
| `StopQAppSession` | End a session |
| `GetQAppSessionMetadata` | Read a session's sharing |
| `UpdateQAppSessionMetadata` | Change a session's sharing |
| `ListQAppSessionData` | List form submissions |
| `ExportQAppSessionData` | Export submissions as CSV |
| `ImportDocument` | Attach a file to a card |
| `CreatePresignedUrl` | Sign a file upload |
| `BatchCreateCategory` | Create categories |
| `BatchUpdateCategory` | Rename categories |
| `BatchDeleteCategory` | Delete categories |
| `ListCategories` | List categories |
| `CreateLibraryItem` | Publish an app version |
| `GetLibraryItem` | Read a library item |
| `UpdateLibraryItem` | Change a library item |
| `UpdateLibraryItemMetadata` | Verify a library item |
| `DeleteLibraryItem` | Unpublish an app |
| `ListLibraryItems` | List the library |
| `AssociateLibraryItemReview` | Rate a library item |
| `DisassociateLibraryItemReview` | Remove a rating |
| `PredictQApp` | Draft an app |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
