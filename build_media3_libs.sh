#!/bin/bash
#
# Build AndroidX Media3 libs that the DolbyCarExperienceTests.apk depends on and copy them to correct
# destination folder.
function usage() {
  echo ""
  echo "Usage: `basename "${BASH_SOURCE}"` [options] DEST_DIR"
  echo "Generate AndroidX Media3 libraries to link against DolbyCarExperienceTests.apk"
  echo ""
  echo "Arguments:"
  echo "  DEST_DIR"
  echo "     Destination directory to store the generated AndroidX Media3 libraries."
  echo ""
  echo "Options:"
  echo "  -h"
  echo "     Show these instructions."
  echo ""
}

function err() {
  echo "[$(date +'%Y-%m-%dT%H:%M:%S%z')]: $@" >&2
}

# Process options...
while getopts ":hsoa" opt ; do
  case $opt in
    h)
      usage
      exit 0
      ;;
    \?)
      err "Unrecognized option '$OPTARG'. Use -h to see usage."
      exit 1
      ;;
    :)
      err "option '$OPTARG' requires an argument. Use -h to see usage."
      exit 1
      ;;
  esac
done
shift $((OPTIND-1))

# DEST_DIR
if [ -z "$1" ] ; then
  err "Missing input arg DEST_DIR. Use -h to see usage."
  exit 1
fi
dest_dir="${1/%\/}" # removes any trailing '/'

# Build libs with testCoverageEnabled flag set to false and copy them to destination folder
libraries=(common container database datasource decoder exoplayer extractor)
for library in "${libraries[@]}"; do
    echo "Building the lib-${library} module."
    ./gradlew :lib-${library}:assembleDebug -PtestCoverageEnabled=false

    lib_src_dir=./libraries/${library}/buildout/outputs/aar

    echo "Copying ${lib_src_dir}/lib-${library}-debug.aar to \
        ${dest_dir}/media3-library-${library}-debug.aar"
    cp ${lib_src_dir}/lib-${library}-debug.aar \
        ${dest_dir}/media3-library-${library}-debug.aar
done
