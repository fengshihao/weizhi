#ifndef WEIZHI_PLUGIN_LOADER_H
#define WEIZHI_PLUGIN_LOADER_H

#include "weizhi.h"

/* Enable built-in typed plugin loader: dir/<name>/manifest.json + lib<name>.so|.dylib|.dll */
int weizhi_enable_plugin_loader(WeizhiEngine *engine, const char *plugin_dir);

#endif
