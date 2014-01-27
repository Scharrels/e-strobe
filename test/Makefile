JIKES_PATH=/home/aftandil/concheapass/jikesrvm-3.1.1-cha2
JIKES_CONFIG=BaseBaseMarkSweep_x86_64-linux

CP=.:${JIKES_PATH}/target/${JIKES_CONFIG}/classes

TestTransitiveClosure.class: TestTransitiveClosure.java
	javac -cp ${CP} TestTransitiveClosure.java

test: TestTransitiveClosure.class
	${JIKES_PATH}/dist/${JIKES_CONFIG}/rvm TestTransitiveClosure

clean:
	rm -f *.class
