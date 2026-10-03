package io.legado.app.help

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

@Suppress("unused")
object LayoutManager {

    interface LayoutManagerFactory {
        fun create(recyclerView: RecyclerView): RecyclerView.LayoutManager
    }

    fun linear(): LayoutManagerFactory {
        return object : LayoutManagerFactory {
            override fun create(recyclerView: RecyclerView): RecyclerView.LayoutManager {
                return LinearLayoutManager(recyclerView.context)
            }
        }
    }


    fun linear(@RecyclerView.Orientation orientation: Int, reverseLayout: Boolean): LayoutManagerFactory {
        return object : LayoutManagerFactory {
            override fun create(recyclerView: RecyclerView): RecyclerView.LayoutManager {
                return LinearLayoutManager(recyclerView.context, orientation, reverseLayout)
            }
        }
    }


    fun grid(spanCount: Int): LayoutManagerFactory {
        return object : LayoutManagerFactory {
            override fun create(recyclerView: RecyclerView): RecyclerView.LayoutManager {
                return GridLayoutManager(recyclerView.context, spanCount)
            }
        }
    }


    fun grid(
        spanCount: Int,
        @RecyclerView.Orientation orientation: Int,
        reverseLayout: Boolean
    ): LayoutManagerFactory {
        return object : LayoutManagerFactory {
            override fun create(recyclerView: RecyclerView): RecyclerView.LayoutManager {
                return GridLayoutManager(
                    recyclerView.context,
                    spanCount,
                    orientation,
                    reverseLayout
                )
            }
        }
    }


    fun staggeredGrid(spanCount: Int, @RecyclerView.Orientation orientation: Int): LayoutManagerFactory {
        return object : LayoutManagerFactory {
            override fun create(recyclerView: RecyclerView): RecyclerView.LayoutManager {
                return StaggeredGridLayoutManager(spanCount, orientation)
            }
        }
    }

}
