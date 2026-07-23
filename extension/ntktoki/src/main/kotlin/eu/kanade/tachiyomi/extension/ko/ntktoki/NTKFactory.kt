package eu.kanade.tachiyomi.extension.ko.ntktoki

import eu.kanade.tachiyomi.source.SourceFactory

class NTKFactory : SourceFactory {
    override fun createSources() = listOf(
        NTKManga(),
        NTKWebtoon(),
    )
}
