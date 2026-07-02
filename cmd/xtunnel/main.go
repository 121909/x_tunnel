package main

import (
	"log"

	"xtunnel"
)

func main() {
	if err := xtunnel.RunCLI(); err != nil {
		log.Fatal(err)
	}
}
