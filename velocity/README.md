# MorghChatVelocity

Velocity companion plugin for the MorghChat TCP network bridge.

## Build

From the repository root run `gradle :velocity:build`. The plugin JAR is in `velocity/build/libs/`.

## Configuration

On first startup, the plugin creates `plugins/morghchatvelocity/config.properties`:

```properties
bind=127.0.0.1
port=25590
secret=CHANGE_THIS_TO_A_RANDOM_SECRET_AT_LEAST_32_CHARACTERS
```

Replace the default secret with a random value of at least 32 characters before connecting a backend. Configure the same host, port, and secret in every Paper backend's MorghChat configuration.

If the proxy and backends share a machine, keep the loopback bind address. Otherwise bind to a private interface and firewall the port so only backend machines can connect. The shared secret authenticates clients but does not encrypt traffic; do not expose the bridge to untrusted networks.

The proxy routes opaque packets and does not parse chat messages or access player data.
