#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Euro-Office contributors
# SPDX-License-Identifier: AGPL-3.0-only

# Self-check of check-translations.sh: each line is the expected exit code and a changed path

cd "$(dirname "$0")" || exit 1
failed=0
mode=
check() {
  local want=$1 got
  shift
  printf "%s\n" "$@" | ./check-translations.sh ${mode:+"$mode"} >/dev/null
  got=$?
  if [[ $got != "$want" ]]; then
    echo "FAIL: expected exit $want, got $got for: $*"
    failed=1
  fi
}

while read -r want file; do
  check "$want" "$file"
done <<EOF
1 libresources/src/main/res/values-de/strings.xml
1 libresources/src/main/res/values-pt-rBR/strings.xml
1 libresources/src/main/res/values-b+pt+BR@formal/strings.xml
1 libgdocs/src/main/res/values-fr/strings.xml
0 libresources/src/main/res/values/strings.xml
0 libresources/src/main/res/values-night/colors.xml
0 libgeditors/src/main/res/values-w720dp/dimens.xml
0 README.md
EOF

# a violation among allowed files fails, no changes pass
check 1 libresources/src/main/res/values/strings.xml libresources/src/main/res/values-de/strings.xml
check 0 ''

# --only-translations passes Weblate's PRs: translations only, nothing else
mode=--only-translations
check 0 libresources/src/main/res/values-de/strings.xml libresources/src/main/res/values-fr/strings.xml
check 1 libresources/src/main/res/values-de/strings.xml libresources/src/main/res/values/strings.xml
check 1 libresources/src/main/res/values-de/strings.xml .github/workflows/translations.yml
check 1 ''

(( failed )) || echo ok
exit $failed
