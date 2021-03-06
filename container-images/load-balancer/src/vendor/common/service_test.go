/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */
package common

import (
	"testing"
	"os"
	"fmt"
)

const testJSONConfigFile = "test/config_input.json"

func TestReadFromInput(t *testing.T) {
	var f, err = os.Open(testJSONConfigFile)
	if err != nil {
		t.Error(err)
	}
	var cfg, errInput = ReadFromInput(f, "")
	if errInput != nil {
		t.Fatalf("Error read JSON configuration file!", errInput)
	}
	AssertEqual(t, getReadFromInputConfig(), cfg)
}

func getReadFromInputConfig() *Config {
	var config = Config{}
	config.Frontends = make([]Frontend, 2, 2)

	config.Frontends[0].Port = 443
	config.Frontends[0].Backends = make([]Backend,2,2)
	config.Frontends[0].Backends[0] = Backend{}
	config.Frontends[0].Backends[0].Port = 443
	config.Frontends[0].Backends[0].Host = "www.dir.bg"
	config.Frontends[0].Backends[1] = Backend{}
	config.Frontends[0].Backends[1].Port = 442
	config.Frontends[0].Backends[1].Host = "www.actualno.bg"

	config.Frontends[1].Port = 80
	config.Frontends[1].Backends = make([]Backend,2,2)
	config.Frontends[1].Backends[0] = Backend{}
	config.Frontends[1].Backends[0].Port = 8080
	config.Frontends[1].Backends[0].Host = "www.novini.bg"
	config.Frontends[1].Backends[1] = Backend{}
	config.Frontends[1].Backends[1].Port = 8008
	config.Frontends[1].Backends[1].Host = "www.vesti.bg"

	return &config
}


func AssertEqual(t *testing.T, expected *Config, actual *Config) {
	if expected == nil && actual == nil {
		fmt.Println("Both configurations are NIL")
		return
	}
	if expected == nil || actual == nil{
		t.Fatal("One of the configuration is NIL!", expected, actual)
	}
	if expected.Frontends == nil && actual.Frontends == nil {
		fmt.Println("Both frontends are NIL")
		return
	}
	if expected.Frontends == nil || actual.Frontends == nil {
		t.Fatal("One of the frontends is NIL!", expected.Frontends, actual.Frontends)
	}
	if len(expected.Frontends) !=  len(actual.Frontends) {
		t.Fatal("The frontends have diferent legth: expected %d - actial %d", len(expected.Frontends), len(actual.Frontends))
	}

	for i := 0; i < len(expected.Frontends); i++ {
		if expected.Frontends[i].Port !=  actual.Frontends[i].Port {
			t.Fatal("The port of the frontends is different! ", expected.Frontends[i].Port, actual.Frontends[i].Port)
		}
		if expected.Frontends[i].Backends == nil && actual.Frontends[i].Backends == nil {
			continue
		}
		if expected.Frontends[i].Backends == nil || actual.Frontends[i].Backends == nil {
			t.Fatal("One of the backgrounds is NIL! ", expected.Frontends[i].Backends, actual.Frontends[i].Backends)
		}
		if len(expected.Frontends[i].Backends) != len(actual.Frontends[i].Backends) {
			t.Fatal("The backgrounds have different length! ", len(expected.Frontends[i].Backends), len(actual.Frontends[i].Backends))
		}
		for j := 0; j < len(expected.Frontends[i].Backends); j++ {
			if expected.Frontends[i].Backends[j].Host !=  actual.Frontends[i].Backends[j].Host {
				t.Fatal("The host of the background is different! ", expected.Frontends[i].Backends[j].Host, actual.Frontends[i].Backends[j].Host)
			}
			if expected.Frontends[i].Backends[j].Port !=  actual.Frontends[i].Backends[j].Port {
				t.Fatal("The port of the background is different! ", expected.Frontends[i].Backends[j].Port, actual.Frontends[i].Backends[j].Port)
			}
		}
	}
}
