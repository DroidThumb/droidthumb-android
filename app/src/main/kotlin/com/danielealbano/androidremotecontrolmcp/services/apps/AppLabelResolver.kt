package com.danielealbano.androidremotecontrolmcp.services.apps

interface AppLabelResolver {
    /** The package's launcher label, or the package name itself if it can't be resolved (app
     *  uninstalled between the step naming it and this lookup, etc. - never throws). */
    fun labelFor(packageName: String): String
}
