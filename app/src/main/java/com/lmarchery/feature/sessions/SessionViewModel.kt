package com.lmarchery.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lmarchery.data.SessionRepository

class SessionViewModel(repository: SessionRepository) : ViewModel() {
    val controller = SessionController(repository, viewModelScope).also { it.refresh() }

    class Factory(private val repository: SessionRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == SessionViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return SessionViewModel(repository) as T
        }
    }
}
