#!/usr/bin/env node
/**
 * verify-fresh.js -- fails if main.js is older than any file under src/main/.
 *
 * Why: a stale compiled main.js shipped while src/main/ had moved on broke
 * /LLM end to end for users, silently, because nothing checked that the
 * build artifact actually reflected the latest source. This script is a
 * cheap mtime comparison (no rebuild) meant to run after every build/release
 * and as part of `npm test`, so a stale bundle surfaces immediately instead
 * of in a user's hands.
 *
 * Cross-platform (Windows + POSIX): pure Node fs, no shell globbing.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '..');
const mainJsPath = path.join(repoRoot, 'main.js');
const srcMainDir = path.join(repoRoot, 'src', 'main');

/** Recursively collects all file paths under dir. */
function collectFiles(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      collectFiles(fullPath, out);
    } else if (entry.isFile()) {
      out.push(fullPath);
    }
  }
  return out;
}

function main() {
  if (!fs.existsSync(mainJsPath)) {
    console.error(
      'verify:fresh -- ' + mainJsPath + ' does not exist.\n' +
      'Run "npx shadow-cljs release app" (or "npm run watch") before testing/releasing.'
    );
    process.exitCode = 1;
    return;
  }

  if (!fs.existsSync(srcMainDir)) {
    console.error('verify:fresh -- ' + srcMainDir + ' does not exist; nothing to compare.');
    process.exitCode = 1;
    return;
  }

  const mainJsMtimeMs = fs.statSync(mainJsPath).mtimeMs;
  const sourceFiles = collectFiles(srcMainDir, []);
  const staleFiles = sourceFiles
    .filter((file) => fs.statSync(file).mtimeMs > mainJsMtimeMs)
    .sort();

  if (staleFiles.length > 0) {
    console.error(
      'verify:fresh -- main.js is STALE: these src/main/ files changed after the last build:'
    );
    for (const file of staleFiles) {
      console.error('  ' + path.relative(repoRoot, file));
    }
    console.error(
      '\nRun "npx shadow-cljs release app" (or "npm run watch") and re-test before shipping.'
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    'verify:fresh -- OK: main.js is newer than all ' + sourceFiles.length +
    ' files under src/main/.'
  );
}

main();
