#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Euro-Office contributors
# SPDX-License-Identifier: AGPL-3.0-only

# Reads changed paths from stdin and fails on translations that Weblate manages
# (https://hosted.weblate.org/projects/euro-office/): a PR changes only the English source.

managed='^.+/src/main/res/values-[^/]+/strings\.xml$'

failed=0
while IFS= read -r file; do
  if [[ $file =~ $managed ]]; then
    echo "::error file=$file::$file is translated on Weblate, change only the English source"
    failed=1
  fi
done
exit $failed
