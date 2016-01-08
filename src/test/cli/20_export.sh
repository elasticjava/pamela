#!/bin/sh
#
# Copyright © 2016 Dynamic Object Language Labs Inc.
#
# This software is licensed under the terms of the
# Apache License, Version 2.0 which can be found in
# the file LICENSE at the root of this distribution.

# do NOT set this as the grep pipe will mess it up
# set -e

# demonstrate sending output to STDOUT
# MUST make fix for lein-cljsbuild polluting STDOUT :(
pamela -o - -m four export | grep -v ^Compiling > $RESULTS/four.pamela

if ! diff -u $CODE/src/test/pamela/four.pamela $RESULTS/four.pamela; then
    exit 1
fi
