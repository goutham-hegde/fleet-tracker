/**
 * Which of the two pages this build is.
 *
 * The same dashboard is built twice. Against the live platform it follows the stream and moves
 * trucks as they report. For the public address (S22) it is built with `VITE_ARCHIVE_MODE=true` and
 * reads a Lambda function that answers from the S3 archive: there is no stream to follow, the answers
 * change once an hour, and the page must say so rather than show an hour-old fleet as though it were
 * moving now.
 *
 * Read from the environment at build time, like the API's address, so the choice is made in one
 * place and cannot flip at runtime. Vite replaces the expression with a constant, and the bundler
 * drops whichever branch a build does not use.
 */
export const ARCHIVE_MODE: boolean = import.meta.env.VITE_ARCHIVE_MODE === 'true';

/** How often the snapshot is re-fetched in archive mode. The lookup's answers are cached for 60s. */
export const ARCHIVE_POLL_MS = 60_000;
