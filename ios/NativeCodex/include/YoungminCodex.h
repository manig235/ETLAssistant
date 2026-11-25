#ifndef YOUNGMIN_CODEX_H
#define YOUNGMIN_CODEX_H
#include <stdint.h>
uint64_t ym_codex_start(const char *home, const char *workspace);
int32_t ym_codex_send(uint64_t handle, const char *json);
char *ym_codex_poll(uint64_t handle);
void ym_codex_free_string(char *message);
void ym_codex_stop(uint64_t handle);
#endif
