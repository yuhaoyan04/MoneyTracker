# Local habit learning

## Purpose and boundaries

Suggest a category for a transaction using explicitly reviewed examples from this device. It is a ranking aid, not a recommendation system that predicts what a person should buy. A suggestion score is not a calibrated probability and does not establish that a payment occurred.

## Feedback and persistence

Manual category selection and explicit inbox confirmation upsert one sample per transaction stable ID. Merely saving an untouched default suggestion does not create a training example; editing an existing reviewed example updates it. A subsequent category edit replaces the old example. Deleting a transaction or clearing a transaction type removes its samples. Automatic confirmation and imported, automatically suggested categories do not train the new model: treating predictions as ground truth can amplify errors. Existing `tagger_model.json` is retained as a cold-start fallback; it is not migrated because its historical feedback provenance is unknown. This means the new model begins empty on upgrade.

The separate `habit_samples_v1.json` file stores at most 2,000 samples locally, using atomic replacement; it currently is not included in ledger exports. A reinstall or device migration therefore resets this model. Existing ledger data is not modified. Failure to read a corrupt model fails closed; it is not silently overwritten. The merchant field, when available, is stored locally; arbitrary payment text is not retained by this model.

## Candidate scoring

For the same transaction type, compare the circular time of day and log-scaled amount. Samples far from both are ignored. Similarities are weighted by age with a 90-day half-life, with small bonuses for the same merchant and channel. Recommendations require at least two supporting samples, a minimum total weight, and at least 70% of local weighted support. Otherwise the system abstains and the legacy fallback handles the transaction. Time and amount alone are inherently ambiguous; none of the thresholds are empirically calibrated for a particular user.

Habit suggestions are returned with an internal score of 0.49 so they cannot meet the existing automatic-confirm threshold of 0.5. This is a safety policy, not a prediction of 49% accuracy. Recommendations are refreshed as the manual-entry amount changes, unless the user has selected a category.

## Evaluation and limitations

The unit tests use chronological synthetic replays (prediction before training on each event), repeated lunch/water/metro patterns, ambiguous cases, edits, income/expense separation, outliers and changed routines. They demonstrate behavior on controlled examples, not real-user accuracy or calibrated confidence. Real-world evaluation should log predictions and later explicit corrections separately, stratify by category and coverage, compare to a simple baseline chronologically, and avoid presenting accepted defaults as unbiased ground truth. No such production metrics are claimed here.

The legacy fallback can still reflect older polluted or stale examples. Further work should replace it after an opt-in migration and evaluation, include provenance-aware model backup and restore, collect useful merchant/item text only with a clear privacy policy, and expose an optional reset and explanations. Sequence signals and online exploration should not be added until they are justified by measured user benefit.

Related research: [temporal dynamics in recommender systems](https://arxiv.org/abs/2104.13030) and [feedback-loop exposure bias](https://arxiv.org/abs/2408.04332).
