"""Zymb: An Asynchronous Python Communication Package
    Designed by Andrew Plotkin <erkyrath@eblong.com>
    Zymb home page: <http://eblong.com/zarf/zymb/>

The Python source code in this module, and its submodules, is
copyright 2005-7 by Andrew Plotkin. You may copy and distribute it
freely, by any means and under any conditions. You may incorporate
this code into your own software and distribute that, or modify this
code and distribute the modified version, as long as you retain a
notice in your software or documentation which includes this copyright
notice, this license, and the URL shown above.

Zymb is a Python framework for handling asynchronous communication and
events. In theory, any communication mechanism could be implemented as
a Zymb agent. In practice, I've got files, TCP sockets, and Jabber
instant messaging.

Zymb requires Python version 2.3 or later.

I do not have top-to-bottom documentation written. (Sorry.) However,
the modules are heavily commented. The comments are in the form of
docstrings, so you can either look at the source code, or use the
help() function from an interactive Python shell.

If you are new to Zymb, start by looking at the zymb.sched module --
in particular, the zymb.sched.Agent class and the zymb.sched.process()
function. You will also want to look at the Zymb example applications
(available at the URL above). They demonstrate using Zymb agents for
simple tasks.

* Version history:

- ####
Fixed handling of uri-digest in SASL authentication.

- 1.3.6:
The rpcdata module was overly paranoid about struct types. (It applied
    the same limitations to struct keys as to method names.) This
    restriction has been removed.

- 1.3.5:
The interface.JID class was overly paranoid about @ signs. Turns out, an
    @ sign in a JID resource is legal -- or at least it's seen in the wild.
    Same goes for / signs.

- 1.3.4:
Fixed several bugs in the send() methods of the tcp module. (Thanks to Phil.)
Fixed the way I was catching SSL socket closed errors.
Added sendnb() methods to send in a non-blocking way, since the send()
    methods are de facto blocking (send-all) methods.

- 1.3.3:
Added a parsefile() utility to the xmldata module.
Fixed a bug which prevented a Jabber agent from logging in with a Unicode
    username or password.

- 1.3.2:
The dataform module has been upgraded to handle field types. Unfortunately
    this changes the result format of DataForm.getfields(). Hopefully this
    won't break anybody's code.

- 1.3.1:
The priority queue system, which is supposed to ensure that the consequences
    of a Jabber message play out completely before the next message is
    processed, never worked right. Now (I think) it does.

- 1.3.0:
Exceptions in scheduled actions are now logged. They no longer kill the
    Zymb process.

- 1.2.7:
Fixed dumb bug when comparing (jid1 != jid2)

- 1.2.6:
Added a hook API to PresenceService, so that an application can customize
    presence stanzas as they're generated.
Added the Entity Capabilities (JEP-0115) URI to interface.py.

- 1.2.5:
Added a way to override the default parsing of Jabber-RPC value tags.
    (On a global basis only -- sorry.)

- 1.2.4:
Added a way to create a Jabber stream which communicates with a server
    different from the one in the JID. (This is necessary to deal with
    talk.google.com, which uses "name@gmail.com" JIDs.)
The Jabber agent now understands the concept of getting a resource from
    the server which is not the one it asked for.
Various other fixes for bugs which were uncovered by Google's Jabber
    implementation.

- 1.2.3:
Fixed the RPC service to disallow empty RPC replies. (I thought that was
    legal, but it isn't.) An RPC handler which returns None will now
    generate a True response.

- 1.2.2:
Added a way to customize the "RPC not found" response of the RPC service.
More comments.

- 1.2.1:
Adjusted the scheduler so that one network message's consequences are
    fully executed before the next message begins.
Added a keepalive service (exercises Jabber connection periodically).

- 1.2:
Implemented XML classes. Zymb now relies only on Python standard library
    modules.
Fixed bug in RPC service (arguments were wrapped in an extraneous array)

- 1.1:
The Jabber client now starts an XMPP session after binding, on
    servers that support the session feature.
Added simple presence service. (Just handles on and off.)

- 1.0:
Initial release. (Why start at 1.0 for a very incomplete release?
    Well, why not, really. It's not like "0.3" would be any more
    meaningful.)
"""

import sys
if (sys.hexversion < 0x2030000):
    raise Exception, 'zymb requires Python version 2.3 or later.'

__all__ = [
    'sched', 'fileagent', 'readlineagent', 'tcp', 'xmlagent', 'xmldata',
    'jabber'
]
