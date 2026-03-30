{
  description = "Markko development environment";

  /*
   * Usage:
   *   nix develop                    # Enter default dev shell with JDK 17
   *   nix develop .#jdk21            # Enter dev shell with JDK 21
   *   nix develop --command sbt compile   # Run a command directly
   *
   * For direnv support, create .envrc with:
   *   use flake
   *
   * Tools included:
   *   - Java JDK 17 (Temurin)
   *   - sbt (Scala Build Tool)
   *   - Metals (Scala LSP)
   *   - Node.js (for frontend)
   */

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true; # For some tools like Metals
        };

        # Java versions
        jdk17 = pkgs.temurin-bin-17;
        jdk21 = pkgs.temurin-bin-21;
        
        jdk = jdk17;

        # Build tools
        sbt = pkgs.sbt;

        # Scala LSP
        metals = pkgs.metals;

        nodejs = pkgs.nodejs_20;

        # Helper function to create a dev shell with a specific JDK
        makeDevShell = jdk: pkgs.mkShell {
          buildInputs = [
            # Java
            jdk

            # Build tools
            sbt

            # Scala LSP
            metals

            nodejs
          ];

          # Environment variables
          JAVA_HOME = "${jdk}";
          METALS_JAVA_HOME = "${jdk}";

          # Shell hooks
          shellHook = ''
            export TMPDIR="/tmp"
            export SBT_GLOBAL_BASE="/tmp/markko-sbt-global"
            export SBT_BOOT_DIR="/tmp/markko-sbt-boot"
            export IVY_HOME="/tmp/markko-ivy2"
            export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath [ pkgs.stdenv.cc.cc.lib ]}:''${LD_LIBRARY_PATH:-}"
            mkdir -p "$SBT_GLOBAL_BASE" "$SBT_BOOT_DIR" "$IVY_HOME"

            if [ -f .env ]; then
              set -a
              . ./.env
              set +a
            fi

            export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp ''${JAVA_TOOL_OPTIONS:-}"
            export _JAVA_OPTIONS="-Djava.io.tmpdir=/tmp ''${_JAVA_OPTIONS:-}"
            export SBT_OPTS="-Xmx2G -XX:+UseG1GC -Djava.io.tmpdir=/tmp -Dsbt.io.tmpdir=/tmp -Dsbt.global.base=$SBT_GLOBAL_BASE -Dsbt.boot.directory=$SBT_BOOT_DIR -Divy.home=$IVY_HOME"

            echo "Welcome to the Markko development environment!"
            echo "Java version: $(java -version 2>&1 | head -1)"
            echo "sbt version: available via 'sbt --version'"
            echo "Available commands:"
            echo "  sbt compile    - Compile the project"
            echo "  sbt test       - Run tests"
            echo "  sbt run        - Run the application"
            echo "Metals LSP is available. Open your editor (VS Code, Vim, etc.)"
            echo "and it should automatically detect the Scala environment."
          '';
        };

      in {
        devShells = {
          default = makeDevShell jdk;
          jdk17 = makeDevShell jdk17;
          jdk21 = makeDevShell jdk21;
        };
      });
}
