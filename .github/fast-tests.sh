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

getTestKitGen()
{
	echo "get testKitGen..."
	git clone https://github.com/AdoptOpenJDK/TKG.git
}

runtest()
{
        cd TKG
	make compile
	make _jdk_custom
}

pwd
printenv
getTestKitGen
java -version
export BUILD_LIST=openjdk
#export TEST_JDK_HOME=$TEST_JDK_HOME
echo "$TEST_JDK_HOME"
runtest
