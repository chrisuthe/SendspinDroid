package com.sendspindroid.sendspin.crypto

// GENERATED FILE - do not edit by hand.
// Regenerate with: python ci/conformance/noise/make_kotlin_vectors.py
//
// Produced by `noiseprotocol` (the library aiosendspin 9.1.0 depends on) acting
// as BOTH parties with every ephemeral pinned, so these are reference vectors
// rather than a recording of our own output.
//
// In Sendspin the SERVER is the Noise initiator and the CLIENT is the responder.
// Everything here is written from the responder's (our) point of view:
// `transportKeyRecv` decrypts `transportI2r`.

/** One complete KKpsk2 transcript for a single cipher suite. */
data class NoiseVector(
    val protocol: String,
    val protocolNameIsHashed: Boolean,
    val prologue: String,
    val psk: String,
    val serverStaticPublic: String,
    val clientStaticPrivate: String,
    val clientStaticPublic: String,
    val clientEphemeralPrivate: String,
    val message1: String,
    val message1PayloadUtf8: String,
    val message2: String,
    val message2PayloadUtf8: String,
    val handshakeHash: String,
    val transportKeyRecv: String,
    val transportKeySend: String,
    val transportI2r: List<String>,
    val transportI2rPlaintextUtf8: List<String>,
    val transportR2i: List<String>,
    val transportR2iPlaintextUtf8: List<String>,
)

object NoiseTestVectors {
    val chaChaPoly = NoiseVector(
        protocol = "Noise_KKpsk2_25519_ChaChaPoly_SHA256",
        protocolNameIsHashed = true,
        prologue = "7b2274797065223a22636c69656e742f696e6974222c227061796c6f6164223a7b22636c69656e745f6964223a22616161222c2276657273696f6e223a312c227375697465223a2232353531395f436861436861506f6c795f534841323536227d7d7b2274797065223a227365727665722f696e6974222c227061796c6f6164223a7b227365727665725f6964223a22626262222c2276657273696f6e223a317d7d",
        psk = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf",
        serverStaticPublic = "358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254",
        clientStaticPrivate = "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f",
        clientStaticPublic = "675dd574ed7789310b3d2e7681f3790b466c773b1521fecf36577958371ea52f",
        clientEphemeralPrivate = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf",
        message1 = "493e82fc74464a59268817623d2053c5eb8e2cc4a988b4fee179ec6b010d531dcdf8933800968c0ab0d21481c22a3498678fbf632fe11d0bc9d7274d3b3483c391fabc3cf8313bca05915051a9bcbc3307ea765898a8d2f07d9d1f51e63a347c25b19f74ecb23b6bca",
        message1PayloadUtf8 = "{\"psk_id\": \"GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo\"}",
        message2 = "dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44a0aee3326536aeda667ac5b81a99a7480a29",
        message2PayloadUtf8 = "{}",
        handshakeHash = "691bc582ce0c4992741a9a7305f8e479df8e3d5f06b6ef084ae67948eef3bdc3",
        transportKeyRecv = "30f64bb8c9054b6bfdfb710b4fcd024ec00a371ca91db99c8fc00bd31f5181e8",
        transportKeySend = "b3a7f911b85c3b284f96804e584d9838f7bf4308e2ad1094dace15582f81b5ed",
        transportI2r = listOf("e0b680e4ca4c03a01a68f91b7b69cadff1259e5e35", "530a0f21011f62831475d02702b043dc87061f8875"),
        transportI2rPlaintextUtf8 = listOf("i2r-0", "i2r-1"),
        transportR2i = listOf("d626980eabce823b83fa4b553fae59fa385372fbc2", "8ddbb58dfcad40f2e2ce60e36c7b74da13bb04b78a"),
        transportR2iPlaintextUtf8 = listOf("r2i-0", "r2i-1"),
    )

    val aesGcm = NoiseVector(
        protocol = "Noise_KKpsk2_25519_AESGCM_SHA256",
        protocolNameIsHashed = false,
        prologue = "7b2274797065223a22636c69656e742f696e6974222c227061796c6f6164223a7b22636c69656e745f6964223a22616161222c2276657273696f6e223a312c227375697465223a2232353531395f436861436861506f6c795f534841323536227d7d7b2274797065223a227365727665722f696e6974222c227061796c6f6164223a7b227365727665725f6964223a22626262222c2276657273696f6e223a317d7d",
        psk = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf",
        serverStaticPublic = "358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254",
        clientStaticPrivate = "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f",
        clientStaticPublic = "675dd574ed7789310b3d2e7681f3790b466c773b1521fecf36577958371ea52f",
        clientEphemeralPrivate = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf",
        message1 = "493e82fc74464a59268817623d2053c5eb8e2cc4a988b4fee179ec6b010d531d9b492f8a64eb2fd72886e5e29dda5569e7db38ae1699788dd685e3fac0c0cd4d0c0f39c89bc248a04050e67e6a09d2ecb852d380323d09f57eef0a6a92c77baa6d2e90659b862ea750",
        message1PayloadUtf8 = "{\"psk_id\": \"GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo\"}",
        message2 = "dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d443122a01d69a62dffd4812b4e2fbb285c5293",
        message2PayloadUtf8 = "{}",
        handshakeHash = "6b990ac8132e7850a14ef0eb70ad6f631bca47697d0f3aee83b28e123ff3d87d",
        transportKeyRecv = "5def0faca3aa0aa91dd3b576975099b8dc519d7fea1d7aa3bef173f69ee46c48",
        transportKeySend = "0be75a9d0550c12ced1ef74b41d95341068bd4eb3d2f90db8b6aae47e93021c9",
        transportI2r = listOf("1d363bb67bae882e4631b93e7bc4a1e72a7e1d6374", "bc5a8bf0404005de3249c1d92c582b2990e9621076"),
        transportI2rPlaintextUtf8 = listOf("i2r-0", "i2r-1"),
        transportR2i = listOf("214bcbd16c75973062f603ae0307fe00b0908b4e25", "0a5420e2deea58ec69bbd2fec83809ef70d3e4ef00"),
        transportR2iPlaintextUtf8 = listOf("r2i-0", "r2i-1"),
    )

    val all = listOf(chaChaPoly, aesGcm)
}
