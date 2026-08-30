/*
 * Independently audits GdefKerningFixture.ttf through HarfBuzz's C ABI.
 *
 * Build this against the pinned HarfBuzz 14.3.0 headers and the checked-in
 * macOS arm64 library. The command and expected output are in PROVENANCE.md.
 */
#include <hb.h>
#include <hb-ot.h>

#include <stdio.h>
#include <stdlib.h>

static hb_blob_t *read_blob(const char *path) {
  FILE *file = fopen(path, "rb");
  if (file == NULL) return NULL;
  if (fseek(file, 0, SEEK_END) != 0) {
    fclose(file);
    return NULL;
  }
  long length = ftell(file);
  if (length < 0 || fseek(file, 0, SEEK_SET) != 0) {
    fclose(file);
    return NULL;
  }
  char *bytes = malloc((size_t)length);
  if (bytes == NULL || fread(bytes, 1, (size_t)length, file) != (size_t)length) {
    free(bytes);
    fclose(file);
    return NULL;
  }
  fclose(file);
  return hb_blob_create(bytes, (unsigned int)length, HB_MEMORY_MODE_WRITABLE,
                        bytes, free);
}

int main(int argc, char **argv) {
  if (argc != 2) {
    fprintf(stderr, "usage: %s GdefKerningFixture.ttf\n", argv[0]);
    return 64;
  }

  hb_blob_t *blob = read_blob(argv[1]);
  if (blob == NULL) {
    fprintf(stderr, "could not read %s\n", argv[1]);
    return 65;
  }
  hb_face_t *face = hb_face_create(blob, 0);
  hb_font_t *font = hb_font_create(face);
  hb_ot_font_set_funcs(font);
  hb_font_set_scale(font, 1000, 1000);

  hb_buffer_t *buffer = hb_buffer_create();
  hb_buffer_set_direction(buffer, HB_DIRECTION_LTR);
  hb_buffer_set_script(buffer, HB_SCRIPT_LATIN);
  hb_buffer_set_language(buffer, hb_language_from_string("en", -1));
  hb_buffer_set_flags(buffer, HB_BUFFER_FLAG_BOT | HB_BUFFER_FLAG_EOT);
  hb_buffer_set_cluster_level(buffer, HB_BUFFER_CLUSTER_LEVEL_MONOTONE_CHARACTERS);
  hb_buffer_add(buffer, 0x0066, 0);
  hb_buffer_add(buffer, 0x0069, 1);
  hb_buffer_add(buffer, 0x0056, 2);

  const char *shapers[] = {"ot", NULL};
  if (!hb_shape_full(font, buffer, NULL, 0, shapers)) {
    fprintf(stderr, "the ot shaper was unavailable\n");
    return 66;
  }

  unsigned int glyph_count = 0;
  hb_glyph_info_t *infos = hb_buffer_get_glyph_infos(buffer, &glyph_count);
  hb_glyph_position_t *positions = hb_buffer_get_glyph_positions(buffer, &glyph_count);
  printf("glyphs:");
  for (unsigned int index = 0; index < glyph_count; index++) {
    printf(" %u+%d@%u", infos[index].codepoint, positions[index].x_advance,
           infos[index].cluster);
  }
  printf("\n");

  unsigned int caret_count = 1;
  hb_position_t *carets = calloc(caret_count, sizeof(hb_position_t));
  unsigned int total_caret_count = hb_ot_layout_get_ligature_carets(
      font, HB_DIRECTION_LTR, 3, 0, &caret_count, carets);
  printf("gdef-caret-count: %u copied: %u\n", total_caret_count, caret_count);
  printf("raw-gdef-carets:");
  for (unsigned int index = 0; index < caret_count; index++) printf(" %d", carets[index]);
  printf("\n");

  free(carets);
  hb_buffer_destroy(buffer);
  hb_font_destroy(font);
  hb_face_destroy(face);
  hb_blob_destroy(blob);
  return 0;
}
