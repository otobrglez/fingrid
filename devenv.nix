{ pkgs, lib, config, inputs, ... }:

let
  pkgs-unstable = import inputs.nixpkgs-unstable {
    inherit (pkgs.stdenv) system;
    config.allowUnfree = true;
  };
in

{
  name = "fingrid";
  packages = [
    pkgs-unstable.just
    pkgs-unstable.nodejs_24
  ];

  languages.java = {
    enable = true;
    jdk.package = pkgs-unstable.jdk25_headless;
  };

  languages.scala = {
    enable = true;
    mill.enable = true;
    mill.package = pkgs-unstable.mill;
  };

  languages.javascript = {
    enable = true;
    package = pkgs-unstable.nodejs_24;

    yarn.enable = true;
    # yarn.install.enable = false;
    yarn.package = pkgs-unstable.yarn-berry;
  };

  env = {
  };
}
