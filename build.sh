#!/bin/bash

# Write a script to build your optimizer in this file 
# (As required by your chosen optimizer language)

set -e

# Compile optimizer and helper IR classes into build/.
mkdir -p build
find materials/src -name "*.java" > build/sources.txt
javac -d build TigerOptimizer.java @build/sources.txt