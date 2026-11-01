#!/bin/bash
export UML_ROOTFS=${UML_ROOTFS:-$HOME/uml/debian.ext4}
export UML_MEMORY=${UML_MEMORY:-512M}
export UML_CONSOLE_LOG=${UML_CONSOLE_LOG:-$HOME/uml/debian-console.log}
exec bash "$HOME/uml/run.sh"
