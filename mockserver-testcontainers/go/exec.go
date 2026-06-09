package mockserver

import "os/exec"

// execCommand is a variable pointing to exec.Command so tests can override it if needed.
var execCommand = exec.Command
