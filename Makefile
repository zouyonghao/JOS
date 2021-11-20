all:
	gcj HelloWorld.java -S
	gcc HelloWorld.s test.c -o HelloWorld

clean:
	rm HelloWorld.s HelloWorld