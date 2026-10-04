package cork

import android.annotation.SuppressLint
import android.content.Context
import androidx.startup.Initializer

@SuppressLint("StaticFieldLeak")
internal lateinit var libraryContext: Context
    private set

internal class LibraryInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        libraryContext = context.applicationContext
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}