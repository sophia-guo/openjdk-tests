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

TEST_JDK_HOME=""
getTestKitGen()
{
	echo "get testKitGen..."
	git clone https://github.com/AdoptOpenJDK/TKG.git
}


testJavaVersion()
{
# use environment variable TEST_JDK_HOME to run java -version
if [[ $TEST_JDK_HOME == "" ]]; then
	TEST_JDK_HOME=$SDKDIR/openjdkbinary/j2sdk-image
fi
_java=${TEST_JDK_HOME}/bin/java
if [ -x ${_java} ]; then
	echo "Run ${_java} -version"
	${_java} -version
else
	echo "${TEST_JDK_HOME}/bin/java does not exist! Searching under TEST_JDK_HOME: ${TEST_JDK_HOME}..."
	# Search javac as java may not be unique
	javac_path=`find ${TEST_JDK_HOME}/ \( -name "javac" -o -name "javac.exe" \)`
	if [[ $javac_path != "" ]]; then
		echo "javac_path: ${javac_path}"
		javac_path_array=(${javac_path//\\n/ })
		_javac=${javac_path_array[0]}

		# for windows, replace \ to /. Otherwise, readProperties() in Jenkins script cannot read \
		if [[ "${_javac}" =~ "javac.exe" ]]; then
			_javac="${_javac//\\//}"
		fi

		java_dir=$(dirname "${_javac}")
		echo "Run: ${java_dir}/java -version"
		${java_dir}/java -version
		TEST_JDK_HOME=${java_dir}/../
		echo "TEST_JDK_HOME=${TEST_JDK_HOME}" > ${TESTDIR}/job.properties
	else
		echo "Cannot find javac under TEST_JDK_HOME: ${TEST_JDK_HOME}!"
		exit 1
	fi
fi
}

runtest()
{
	cd TKG
	make compile
	make _jdk_custom
}

getTestKitGen

export BUILD_LIST=openjdk
export TEST_JDK_HOME=$TEST_JDK_HOME

runtest
