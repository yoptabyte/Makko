{
  description = "Fiscala Scala development environment";

  /*
   * Usage:
   *   nix develop                    # Enter default dev shell with JDK 17
   *   nix develop .#jdk21            # Enter dev shell with JDK 21 (if added)
   *   nix develop --command sbt compile   # Run a command directly
   *
   * For direnv support, create .envrc with:
   *   use flake
   *
   * Tools included:
   *   - Java JDK 17 (Temurin)
   *   - sbt (Scala Build Tool)
   *   - mill (Scala build tool)
   *   - Metals (Scala LSP)
   *   - Node.js (for frontend)
   *   - python3
   *
   * The Java version matches the project's Dockerfile (Java 17).
   *
   * NOTE: Mill may create an `out/` directory in the project root.
   * If you encounter errors about unsupported file types (sockets),
   * delete the `out/` directory before running nix commands:
   *   rm -rf out
   * The .gitignore file already excludes `out/`, but Mill may recreate it.
   */

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true; # For some tools like Metals
        };

        # Java versions - using Temurin (Eclipse Temurin) as per Dockerfile
        jdk17 = pkgs.temurin-bin-17;
        jdk21 = pkgs.temurin-bin-21;
        
        jdk = jdk17;

        # Build tools
        sbt = pkgs.sbt;
        mill = pkgs.mill;

        # Scala LSP
        metals = pkgs.metals;

        nodejs = pkgs.nodejs_20;
        python3 = pkgs.python3;

        # Helper function to create a dev shell with a specific JDK
        makeDevShell = jdk: pkgs.mkShell {
          buildInputs = [
            # Java
            jdk

            # Build tools
            sbt
            mill

            # Scala LSP
            metals

            nodejs
            python3
          ];

          # Environment variables
          JAVA_HOME = "${jdk}";
          SBT_OPTS = "-Xmx2G -XX:+UseG1GC";
          METALS_JAVA_HOME = "${jdk}";

          # Shell hooks
          shellHook = ''
            export TMPDIR="/tmp"
            export SBT_GLOBAL_BASE="/tmp/fiscala-sbt-global"
            export SBT_BOOT_DIR="/tmp/fiscala-sbt-boot"
            export IVY_HOME="/tmp/fiscala-ivy2"
            export MILL_OUT_DIR="/tmp/fiscala-mill-out"
            export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath [ pkgs.stdenv.cc.cc.lib ]}:''${LD_LIBRARY_PATH:-}"
            mkdir -p "$SBT_GLOBAL_BASE" "$SBT_BOOT_DIR" "$IVY_HOME" "$MILL_OUT_DIR"

            if [ -f .env ]; then
              set -a
              . ./.env
              set +a
            fi

            export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp ''${JAVA_TOOL_OPTIONS:-}"
            export _JAVA_OPTIONS="-Djava.io.tmpdir=/tmp ''${_JAVA_OPTIONS:-}"
            export SBT_OPTS="-Xmx2G -XX:+UseG1GC -Djava.io.tmpdir=/tmp -Dsbt.io.tmpdir=/tmp -Dsbt.global.base=$SBT_GLOBAL_BASE -Dsbt.boot.directory=$SBT_BOOT_DIR -Divy.home=$IVY_HOME"

            echo "Welcome to Fiscala Scala development environment!"
            echo "Java version: $(java -version 2>&1 | head -1)"
            echo "sbt version: available via 'sbt --version'"
            # Use --no-server to avoid creating out directory
            echo "mill version: $(mill --version --no-server 2>&1 | head -1)"
            echo "Available commands:"
            echo "  sbt compile    - Compile the project"
            echo "  sbt test       - Run tests"
            echo "  sbt run        - Run the application"
            echo "  mill __.compile - Mill compile"
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
