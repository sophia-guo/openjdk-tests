#!/usr/bin/env bash
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


###################################################################
## This script walks through all external tests                  ##
## and determines which virtualisation to use                    ##
## It recognizes two environment variables:                      ##
## EXTERNAL_AQA_RUNNER=docker|podman|...                         ##
## EXTERNAL_AQA_SUDO=sudo||runas                                 ##
##                                                               ##
## EXTERNAL_AQA_RUNNER defaults to podman if podman is installed ##
##                     otherwise to docker                       ##
## EXTERNAL_AQA_SUDO defaults to empty string                    ##
###################################################################

if [ -z "${EXTERNAL_AQA_RUNNER}" ]; then
  if which podman > /dev/null; then
     EXTERNAL_AQA_RUNNER=podman
  else
     EXTERNAL_AQA_RUNNER=docker
  fi
fi

function getExternalImageBuildCommand() {
  #"sudo docker build"
  echo "$(getExternalImageCommand) build"
}

function getExternalImageCommand() {
  #"sudo docker"
  echo "${EXTERNAL_AQA_SUDO} ${EXTERNAL_AQA_RUNNER}"
}

function getProviderNice() {
  echo "${EXTERNAL_AQA_RUNNER}"
}

function getSudoNice() {
  if [ -z "${EXTERNAL_AQA_SUDO}" ]; then
    echo "not-as-root"
  else
    echo "${EXTERNAL_AQA_SUDO}"
  fi
}

function getProviderTile() {
  echo "$(getSudoNice)/$(getProviderNice)"
}

