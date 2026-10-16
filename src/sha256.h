#ifndef WEIZHI_SHA256_H
#define WEIZHI_SHA256_H

#include <stddef.h>
#include <stdint.h>

/* FIPS 180-4 SHA-256. data may be NULL when len is 0. out is 32 bytes. */
void weizhi_sha256(const uint8_t *data, size_t len, uint8_t out[32]);

#endif
