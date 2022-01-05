### Link prediction

This function is a direct application of the Gephi plugin by Marco Romanutti and Saskia Schüler, supervised by Michael Henninger at FHNW.

Their code is visible [on Github](https://github.com/romanutti/gephi-plugins/tree/master/modules/LinkPrediction).

The prediction is based on __preferential attachement__. It is limited to undirected, unweighted networks. The reasoning is simple: the most likely link to be created is the one between two nodes which have the most neighbors, but don't have a connection yet.

How to interpret this link prediction? The absence of a link can mean that:

* There is no potential for this link (it is not "relevant" to the nodes that would be involved)
* There is a potential for this link to get created, and this potential is not actualized yet 
* There is a potential for the link but the two nodes choose not to actualize the link

This means that "predicting" a link can address one of these three cases.

Research on link prediction is fascinating. Are you an emlyon business school student? Get in touch if you'd be interested in researching the topic in an MSc thesis! 
