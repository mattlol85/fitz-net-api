// Removes all Overwatch tracker data left behind after the feature was removed.
//
// Usage (mongosh):
//   mongosh "<connection-string>" scripts/cleanup-overwatch-data.js
//
// Example against a local dev instance:
//   mongosh "mongodb://localhost:27017/test" scripts/cleanup-overwatch-data.js
//
// What it does:
//   1. Drops the `overwatch_rating_snapshots` collection (former OverwatchRatingSnapshot documents).
//   2. Unsets the embedded `overwatch` field from every document in `users`
//      (former User.overwatch / OverwatchProfile field).
//
// The script is idempotent — running it again on already-cleaned data is a no-op.

const snapshotCollection = "overwatch_rating_snapshots";

if (db.getCollectionNames().includes(snapshotCollection)) {
  const result = db.getCollection(snapshotCollection).drop();
  print(`Dropped collection '${snapshotCollection}': ${result}`);
} else {
  print(`Collection '${snapshotCollection}' does not exist — skipping.`);
}

const updateResult = db.users.updateMany(
  { overwatch: { $exists: true } },
  { $unset: { overwatch: "" } }
);
print(
  `Unset 'overwatch' field on users: matched=${updateResult.matchedCount}, modified=${updateResult.modifiedCount}`
);

print("Overwatch data cleanup complete.");
